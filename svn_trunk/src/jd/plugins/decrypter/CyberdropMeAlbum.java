//jDownloader - Downloadmanager
//Copyright (C) 2009  JD-Team support@jdownloader.org
//
//This program is free software: you can redistribute it and/or modify
//it under the terms of the GNU General Public License as published by
//the Free Software Foundation, either version 3 of the License, or
//(at your option) any later version.
//
//This program is distributed in the hope that it will be useful,
//but WITHOUT ANY WARRANTY; without even the implied warranty of
//MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//GNU General Public License for more details.
//
//You should have received a copy of the GNU General Public License
//along with this program.  If not, see <http://www.gnu.org/licenses/>.
package jd.plugins.decrypter;

import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.appwork.utils.Regex;
import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.SizeFormatter;
import org.jdownloader.plugins.controller.UpdateRequiredClassNotFoundException;
import org.jdownloader.plugins.controller.host.HostPluginController;
import org.jdownloader.plugins.controller.host.LazyHostPlugin;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.nutils.encoding.Encoding;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DecrypterRetryException;
import jd.plugins.DecrypterRetryException.RetryReason;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.PluginForHost;

@DecrypterPlugin(revision = "$Revision: 51227 $", interfaceVersion = 3, names = {}, urls = {})
public class CyberdropMeAlbum extends PluginForDecrypt {
    public CyberdropMeAlbum(PluginWrapper wrapper) {
        super(wrapper);
    }

    /**
     * Those are all file-extensions which are whitelisted according to their upload form on the main page plus some video extensions. </br>
     * Last updated: 2023-08-09
     */
    private static final String EXTENSIONS                     = "(?i)(?:mp4|m4v|mp3|mov|jpe?g|zip|rar|png|gif|ts|webp|bmp|tiff?|[a-z0-9]{3})";
    public static final Pattern PATTERN_ALBUM                  = Pattern.compile("/a/([A-Za-z0-9]+)", Pattern.CASE_INSENSITIVE);
    public static final String  TYPE_FS                        = "(?i)https?://fs-(\\d+)\\.[^/]+/(.*?\\." + EXTENSIONS + ")";
    public static final Pattern PATTERN_SINGLE_FILE            = Pattern.compile("/(?:e|f)/([A-Za-z0-9]+)", Pattern.CASE_INSENSITIVE);
    public static final Pattern PATTERN_SINGLE_FILE_SHORT_LINK = Pattern.compile("/([^/]+)$", Pattern.CASE_INSENSITIVE);
    private PluginForHost       plugin                         = null;

    @Override
    public Browser createNewBrowserInstance() {
        final Browser br = super.createNewBrowserInstance();
        br.setFollowRedirects(true);
        return br;
    }

    public final static String MAIN_CYBERDROP_DOMAIN = "cyberdrop.me";

    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForDecrypt, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { MAIN_CYBERDROP_DOMAIN, "cyberdrop.to", "cyberdrop.cc", "cyberdrop.ch" });
        return ret;
    }

    public static List<String> getDeadDomains() {
        return Arrays.asList(new String[] { "cyberdrop.to" });
    }

    public static String[] getAnnotationNames() {
        return buildAnnotationNames(getPluginDomains());
    }

    @Override
    public String[] siteSupportedNames() {
        return buildSupportedNames(getPluginDomains());
    }

    public static String[] getAnnotationUrls() {
        return buildAnnotationUrls(getPluginDomains());
    }

    public static String[] buildAnnotationUrls(final List<String[]> pluginDomains) {
        final List<String> ret = new ArrayList<String>();
        for (final String[] domains : pluginDomains) {
            String regex = "https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "(" + PATTERN_ALBUM.pattern() + "|" + PATTERN_SINGLE_FILE.pattern() + "|" + PATTERN_SINGLE_FILE_SHORT_LINK.pattern() + ")";
            regex += "|https?://fs-\\d+\\." + buildHostsPatternPart(domains) + "/.*?\\." + EXTENSIONS;// TYPE_FS
            ret.add(regex);
        }
        return ret.toArray(new String[0]);
    }

    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        final String contenturl = param.getCryptedUrl().replaceFirst("(?i)http://", "https://");
        final String singleFileURL = isSingleMediaURL(contenturl);
        String path = new URL(contenturl).getPath();
        if (new Regex(path, CyberdropMeAlbum.PATTERN_SINGLE_FILE).patternFind()) {
            /* Single file -> Handle via hosterplugin */
            this.ensureInitHosterplugin();
            final DownloadLink singlefile = this.createDownloadlink(contenturl);
            singlefile.setDefaultPlugin(plugin);
            ret.add(singlefile);
        } else if (singleFileURL != null) {
            /* Single direct downloadable file -> Handle via hosterplugin */
            add(ret, null, param.getCryptedUrl(), null, null, null, null);
        } else if (new Regex(path, PATTERN_ALBUM).patternFind()) {
            /* Most likely we have an album or similar: One URL which leads to more URLs. */
            String contentURL = param.getCryptedUrl();
            final String hostFromAddedURLWithoutSubdomain = new URL(contentURL).getHost();
            final List<String> deadDomains = getDeadDomains();
            if (deadDomains != null && deadDomains.contains(hostFromAddedURLWithoutSubdomain)) {
                contentURL = param.getCryptedUrl().replaceFirst(Pattern.quote(hostFromAddedURLWithoutSubdomain) + "/", getHost() + "/");
                logger.info("Corrected domain in added URL: " + hostFromAddedURLWithoutSubdomain + " --> " + getHost());
            }
            final HashSet<String> dups = new HashSet<String>();
            br.getPage(contentURL);
            if (br.getHttpConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            /* Double-check for offline / empty album. */
            final String albumID = new Regex(br._getURL().getPath(), PATTERN_ALBUM).getMatch(0);
            int numberofFiles = -1;
            final String numberofFilesStr = br.getRegex("id=\"totalFilesAmount\"[^>]*>\\s*(\\d+)").getMatch(0);
            if (numberofFilesStr != null) {
                numberofFiles = Integer.parseInt(numberofFilesStr);
            } else {
                logger.warning("Failed to find number of files in this album");
            }
            final String buildID = br.getRegex("\"buildId\"\\s*:\\s*\"([^\"]+)").getMatch(0);
            if (albumID != null && buildID != null) {
                final Browser brc = br.cloneBrowser();
                brc.getPage("/_next/data/" + Encoding.urlEncode(buildID) + "/a/" + albumID + ".json");
                if (brc.getHttpConnection().getResponseCode() == 404) {
                    /* {"notFound":true} */
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                }
            }
            final String albumjs = br.getRegex("const albumData\\s*=\\s*(\\{.*?\\})").getMatch(0);
            String albumTitle = null;
            String albumDescription = null;
            if (albumjs != null) {
                albumTitle = new Regex(albumjs, "name\\s*:\\s*'([^\\']+)'").getMatch(0);
                albumDescription = new Regex(albumjs, "description\\s*:\\s*(?:'|`)([^\\'`]+)").getMatch(0);
            }
            if (albumTitle == null) {
                /* 2023-01-03 */
                albumTitle = br.getRegex("<h1 id=\"title\"[^>]*>([^<]+)</h1>").getMatch(0);
            }
            if (albumDescription == null) {
                albumDescription = br.getRegex("<span id=\"description-box\"[^>]*>([^<]+)</span>").getMatch(0);
            }
            if (albumDescription != null) {
                albumDescription = Encoding.htmlDecode(albumDescription).trim();
            }
            if (albumTitle != null) {
                albumTitle = Encoding.htmlDecode(albumTitle).trim();
            } else {
                /* Fallback */
                albumTitle = albumID;
            }
            final String json = br.getRegex("dynamicEl\\s*:\\s*(\\[\\s*\\{.*?\\])").getMatch(0);
            if (json != null) {
                /* gallery mode only works for images */
                final List<Map<String, Object>> ressourcelist = (List<Map<String, Object>>) JavaScriptEngineFactory.jsonToJavaObject(json);
                for (final Map<String, Object> photo : ressourcelist) {
                    final String downloadUrl = (String) photo.get("downloadUrl");
                    final String subHtml = (String) photo.get("subHtml");
                    final String filesizeStr = new Regex(subHtml, "(\\d+(\\.\\d+)? [A-Za-z]{2,5})$").getMatch(0);
                    add(ret, dups, downloadUrl, null, null, filesizeStr, true);
                }
            }
            final String[] htmls = br.getRegex("<div class=\"image-container column\"[^>]*>(.*?)/p>\\s*</div>").getColumn(0);
            if (htmls != null && htmls.length > 0) {
                for (final String html : htmls) {
                    final String filename = parseMediaFilename(br, html);
                    String url = new Regex(html, "href\\s*=\\s*\"(https?://[^\"]+)\"").getMatch(0);
                    if (url == null) {
                        url = new Regex(html, "href\\s*=\\s*\"(/(?:d|f|v)/[^\"]+)\"").getMatch(0);
                    }
                    if (url != null) {
                        url = br.getURL(url).toExternalForm();
                        final String filesizeBytes = new Regex(html, "class=\"(?:is-hidden)?\\s*file-size\"[^>]*>\\s*(\\d+) B").getMatch(0);
                        final String filesize = new Regex(html, "class=\"(?:is-hidden)?\\s*file-size\"[^>]*>\\s*([0-9\\.]+\\s+[MKG]B)").getMatch(0);
                        add(ret, dups, url, filename, filesizeBytes, filesize, true);
                    }
                }
            }
            if (ret.isEmpty()) {
                if (numberofFiles == 0 || br.containsHTML("(?i)There are no files in the album")) {
                    throw new DecrypterRetryException(RetryReason.EMPTY_FOLDER);
                } else {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
            }
            final FilePackage fp = FilePackage.getInstance();
            if (albumTitle != null) {
                fp.setName(albumTitle);
            } else {
                fp.setName(albumID);
            }
            fp.setAllowInheritance(true);
            if (!StringUtils.isEmpty(albumDescription)) {
                fp.setComment(albumDescription);
            }
            fp.addLinks(ret);
        } else if (new Regex(path, CyberdropMeAlbum.PATTERN_SINGLE_FILE_SHORT_LINK).patternFind()) {
            br.setFollowRedirects(false);
            br.getPage(contenturl);
            if (br.getHttpConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            final String redirect = br.getRedirectLocation();
            if (redirect == null) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else if (!new Regex(new URL(redirect).getPath(), CyberdropMeAlbum.PATTERN_SINGLE_FILE).patternFind()) {
                logger.warning("Redirect to unknown/unsupported format -> Assume that this link is offline");
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            ret.add(this.createDownloadlink(redirect));
        } else {
            /* Invalid URL -> Developer mistake */
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        return ret;
    }

    private DownloadLink add(final List<DownloadLink> ret, Set<String> dups, final String directurl, String filename, final String filesizeBytes, final String filesize, final Boolean setOnlineStatus) throws Exception {
        if (dups != null && !dups.add(directurl)) {
            return null;
        }
        ensureInitHosterplugin();
        final DownloadLink dl = this.createDownloadlink(directurl);
        if (setOnlineStatus != null) {
            dl.setAvailable(setOnlineStatus.booleanValue());
        }
        // direct assign the dedicated hoster plugin because it does not have any URL regex
        dl.setDefaultPlugin(plugin);
        if (filename != null) {
            dl.setFinalFileName(Encoding.htmlDecode(filename).trim());
        }
        if (filesizeBytes != null) {
            dl.setVerifiedFileSize(Long.parseLong(filesizeBytes));
        } else if (filesize != null) {
            dl.setDownloadSize(SizeFormatter.getSize(filesize));
        }
        ret.add(dl);
        return dl;
    }

    private void ensureInitHosterplugin() throws PluginException {
        if (plugin == null) {
            try {
                final LazyHostPlugin lazyHostPlugin = HostPluginController.getInstance().get(getHost());
                if (lazyHostPlugin == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                } else {
                    plugin = lazyHostPlugin.getPrototype(null, false);
                }
            } catch (UpdateRequiredClassNotFoundException e) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, null, e);
            }
        }
    }

    private String parseMediaFilename(Browser br, String html) {
        String filename = new Regex(html, "target\\s*=\\s*\"_blank\"\\s*title\\s*=\\s*\"([^<>\"]+)\"").getMatch(0);
        if (filename == null) {
            // bunkr.is
            filename = new Regex(html, "<p\\s*class\\s*=\\s*\"name\"\\s*>\\s*(.*?)\\s*<").getMatch(0);
            if (filename == null) {
                filename = new Regex(html, "<div\\s*class\\s*=\\s*\"[^\"]*details\"\\s*>\\s*<p\\s*class[^>]*>\\s*(.*?)\\s*<").getMatch(0);
            }
        }
        return filename;
    }

    /**
     * Returns URL if given URL looks like it is pointing to a single file. </br>
     * Returns null if given URL-structure is unknown or does not seem to point to a single file.
     */
    private String isSingleMediaURL(final String url) {
        if (url == null) {
            return null;
        } else if (url.matches(TYPE_FS)) {
            // cyberdrop
            return url;
        } else {
            /* Unknown URL */
            return null;
        }
    }

    @Override
    public int getMaxConcurrentProcessingInstances() {
        return 1;
    }
}
