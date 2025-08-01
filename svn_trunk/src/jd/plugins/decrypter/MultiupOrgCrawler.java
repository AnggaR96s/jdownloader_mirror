//    jDownloader - Downloadmanager
//    Copyright (C) 2013  JD-Team support@jdownloader.org
//
//    This program is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with this program.  If not, see <http://www.gnu.org/licenses/>.
package jd.plugins.decrypter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.SizeFormatter;
import org.jdownloader.captcha.v2.challenge.cloudflareturnstile.AbstractCloudflareTurnstileCaptcha;
import org.jdownloader.captcha.v2.challenge.cloudflareturnstile.CaptchaHelperCrawlerPluginCloudflareTurnstile;
import org.jdownloader.captcha.v2.challenge.hcaptcha.AbstractHCaptcha;
import org.jdownloader.captcha.v2.challenge.hcaptcha.CaptchaHelperCrawlerPluginHCaptcha;
import org.jdownloader.captcha.v2.challenge.recaptcha.v2.CaptchaHelperCrawlerPluginRecaptchaV2;
import org.jdownloader.plugins.components.antiDDoSForDecrypt;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;

@DecrypterPlugin(revision = "$Revision: 51173 $", interfaceVersion = 2, names = {}, urls = {})
public class MultiupOrgCrawler extends antiDDoSForDecrypt {
    // DEV NOTES:
    // DO NOT REMOVE COMPONENTS YOU DONT UNDERSTAND! When in doubt ask raztoki to fix.
    //
    // break down of link formats, old and dead formats work with uid transfered into newer url structure.
    // /?lien=842fab872a0a9618f901b9f4ea986d47_bawls_doctorsdiary202.avi = dead url structure phased out
    // /fichiers/download/d249b81f92d7789a1233e500a0319906_FIQHwASOOL_75_rar = old url structure, but redirects
    // (/fr)?/download/d249b81f92d7789a1233e500a0319906/FIQHwASOOL_75_rar, = new link structure!
    //
    // uid and filename are required to be a valid links for all link structures!
    public MultiupOrgCrawler(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public Browser createNewBrowserInstance() {
        final Browser br = super.createNewBrowserInstance();
        br.setFollowRedirects(true);
        return br;
    }

    private static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForHost, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "multiup.io", "multiup.eu", "multiup.org" });
        return ret;
    }

    protected List<String> getDeadDomains() {
        final ArrayList<String> deadDomains = new ArrayList<String>();
        deadDomains.add("multiup.org");
        return deadDomains;
    }

    public static String[] getAnnotationNames() {
        return buildAnnotationNames(getPluginDomains());
    }

    @Override
    public String[] siteSupportedNames() {
        return buildSupportedNames(getPluginDomains());
    }

    public static String[] getAnnotationUrls() {
        final List<String> ret = new ArrayList<String>();
        for (final String[] domains : getPluginDomains()) {
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/(?:en|fr/)?(fichiers/download/[a-z0-9]{32}_[^<> \"'&%]+|([a-z]{2}/)?(download|mirror)/[a-z0-9]{32}(/[^<> \"'&%]+)?|\\?lien=[a-z0-9]{32}_[^<> \"'&%]+|[a-f0-9]{32}|project/[a-f0-9]{32})");
        }
        return ret.toArray(new String[0]);
    }

    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        String contenturl = param.getCryptedUrl();
        contenturl = contenturl.replaceFirst("(?i)/(en|fr)/", "/en/");
        contenturl = contenturl.replaceFirst("^(?i)http://", "https://");
        final String hostFromAddedURLWithoutSubdomain = Browser.getHost(contenturl, false);
        final List<String> deadDomains = getDeadDomains();
        if (deadDomains != null && deadDomains.contains(hostFromAddedURLWithoutSubdomain)) {
            contenturl = param.getCryptedUrl().replaceFirst(Pattern.quote(hostFromAddedURLWithoutSubdomain) + "/", getHost() + "/");
            logger.info("Corrected domain in added URL: " + hostFromAddedURLWithoutSubdomain + " --> " + getHost());
        }
        final String projectID = new Regex(contenturl, "/project/([a-f0-9]{32})").getMatch(0);
        if (projectID != null) {
            /* Crawl all file links of a "project" (like a folder of files) */
            getPage(contenturl);
            if (br.getHttpConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            final String[] urls = br.getRegex("(/[a-f0-9]{32})").getColumn(0);
            if (urls == null || urls.length == 0) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            String title = br.getRegex("name=\"description\"[^>]*content=\"Show files in the project ([^\"]+)\"").getMatch(0);
            if (title != null) {
                title = Encoding.htmlDecode(title).trim();
                title = title.replace(" (" + projectID + ")", "");
            } else {
                /* Fallback */
                title = projectID;
            }
            final FilePackage fp = FilePackage.getInstance();
            fp.setName(title);
            for (final String url : urls) {
                final DownloadLink link = this.createDownloadlink(br.getURL(url).toExternalForm());
                link._setFilePackage(fp);
                ret.add(link);
            }
        } else {
            /* Crawl all mirrors to a single file */
            String uid = getFUID(contenturl);
            String filename = getFilename(contenturl);
            String filesize = getFileSize(contenturl);
            if (filename != null && uid != null) {
                contenturl = new Regex(contenturl, "(https?://[^/]+)").getMatch(0) + "/download/" + uid + "/" + filename;
            }
            getPage(contenturl);
            if (uid == null) {
                getFUID(br.getURL());
                if (uid == null) {
                    /* Invalid URL / content offline */
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                }
            }
            final String csrftoken = br.getRegex("_csrf_token\"\\s*value\\s*=\\s*\"(.*?)\"").getMatch(0);
            final String mirror = contenturl.replace("/en/download/", "/en/mirror/").replace("/download/", "/en/mirror/");
            if (csrftoken != null) {
                postPage(mirror, "_csrf_token=" + Encoding.urlEncode(csrftoken));
            } else {
                getPage(mirror);
            }
            final String webSiteFilename = getWebsiteFileName(br);
            if (!StringUtils.isEmpty(webSiteFilename)) {
                filename = webSiteFilename;
            }
            final String webSiteFileSize = getWebsiteFileSize(br);
            if (filesize == null) {
                filesize = webSiteFileSize;
            }
            if (br.getHttpConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else if (br.containsHTML("The file does not exist any more\\.<|<h1>\\s*The server returned a \"404 Not Found\"\\.</h2>|<h1>\\s*Oops! An Error Occurred\\s*</h1>|>\\s*File not found|>\\s*No link currently available")) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            final Form captchaform = br.getFormbyActionRegex("/mirror/");
            if (AbstractCloudflareTurnstileCaptcha.containsCloudflareTurnstileClass(br)) {
                if (captchaform == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                final String cfTurnstileResponse = new CaptchaHelperCrawlerPluginCloudflareTurnstile(this, br).getToken();
                captchaform.put("cf-turnstile-response", Encoding.urlEncode(cfTurnstileResponse));
                submitForm(captchaform);
                if (AbstractCloudflareTurnstileCaptcha.containsCloudflareTurnstileClass(br)) {
                    /* This should never happen */
                    throw new PluginException(LinkStatus.ERROR_CAPTCHA);
                }
            } else if (AbstractHCaptcha.containsHCaptcha(br)) {
                if (captchaform == null) {
                    /* This should never happen */
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                final String response = new CaptchaHelperCrawlerPluginHCaptcha(this, br).getToken();
                captchaform.put("h-captcha-response", Encoding.urlEncode(response));
                captchaform.put("g-recaptcha-response", Encoding.urlEncode(response));
                submitForm(captchaform);
                if (AbstractHCaptcha.containsHCaptcha(br)) {
                    /* This should never happen */
                    throw new PluginException(LinkStatus.ERROR_CAPTCHA);
                }
            } else if (br.containsHTML("g-recaptcha")) {
                final String response = new CaptchaHelperCrawlerPluginRecaptchaV2(this, br).getToken();
                captchaform.put("g-recaptcha-response", Encoding.urlEncode(response));
                submitForm(captchaform);
                if (br.containsHTML("g-recaptcha")) {
                    /* This should never happen */
                    throw new PluginException(LinkStatus.ERROR_CAPTCHA);
                }
            }
            String[] urls = br.getRegex("\\s+link\\s*=\\s*\"((https?://)?[^\"]+)\"\\s+").getColumn(0);
            if (urls == null || urls.length == 0) {
                urls = br.getRegex("\\s+href\\s*=\\s*\"([^\"]+)\"\\s+").getColumn(0);
                if (urls == null || urls.length == 0) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
            }
            int index = -1;
            for (final String url : urls) {
                index++;
                logger.info("Crawling mirror " + index + "/" + urls.length);
                if (isAbort()) {
                    break;
                }
                String finalURL = null;
                if (StringUtils.containsIgnoreCase(url, "/redirect-to-host/")) {
                    final Browser brc = br.cloneBrowser();
                    brc.setFollowRedirects(false);
                    brc.getPage(url);
                    boolean retry = true;
                    while (!isAbort()) {
                        finalURL = brc.getRedirectLocation();
                        if (finalURL == null) {
                            finalURL = brc.getRegex("http-equiv\\s*=\\s*\"refresh\"\\s*content\\s*=\\s*\"[^\"]*url\\s*=\\s*(.*?)\"").getMatch(0);
                            if (finalURL != null) {
                                if (retry && StringUtils.containsIgnoreCase(finalURL, "multinews.me")) {
                                    // first/randomly opens sort of *show some ads* website, on retry the real destination is given
                                    retry = false;
                                    brc.getPage(url);
                                    continue;
                                }
                                if (!StringUtils.containsIgnoreCase(finalURL, "/redirect-to-host/") || finalURL.startsWith("http")) {
                                    break;
                                }
                            }
                            if (finalURL != null) {
                                if (finalURL.matches("^.+/\\d+$")) {
                                    // seems we can skip the redirects
                                    finalURL = finalURL.substring(0, finalURL.length() - 2);
                                }
                                sleep(1000, param);
                                finalURL = brc.getURL(finalURL).toString();
                                brc.setRequest(null);
                                brc.setCurrentURL(null);
                                brc.getPage(finalURL);
                            } else {
                                finalURL = brc.getRedirectLocation();
                                if (finalURL == null) {
                                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                                }
                            }
                        } else {
                            break;
                        }
                    }
                } else if (url.startsWith("http")) {
                    finalURL = url.trim().replaceFirst(":/+", "://");
                }
                if (finalURL != null) {
                    final DownloadLink downloadLink = createDownloadlink(finalURL);
                    if (filename != null) {
                        downloadLink.setFinalFileName(filename);
                    }
                    if (filesize != null) {
                        downloadLink.setDownloadSize(SizeFormatter.getSize(filesize));
                    }
                    distribute(downloadLink);
                    ret.add(downloadLink);
                }
            }
        }
        return ret;
    }

    private String multiNewsWorkaround = null;

    @Override
    protected void getPage(String page) throws Exception {
        final boolean workaround = multiNewsWorkaround == null;
        try {
            if (workaround) {
                multiNewsWorkaround = page;
            }
            super.getPage(page);
            if (br.containsHTML("<title>\\s*Redirect\\s*</title>")) {
                final String location = br.getRegex("window\\.opener\\.location\\s*=\\s*'([^']+)'\\s*;").getMatch(0);
                if (location != null) {
                    Thread.sleep(1000);
                    if (StringUtils.containsIgnoreCase(location, "multinews.me")) {
                        getPage(multiNewsWorkaround);
                    } else {
                        getPage(location);
                    }
                } else {
                    final String redirect = br.getRegex("http-equiv=\"refresh\" content=\"\\d+;\\s*url\\s*=\\s*([^<>\"]+)\"").getMatch(0);
                    if (redirect != null) {
                        if (StringUtils.containsIgnoreCase(redirect, "multinews.me")) {
                            Thread.sleep(1000);
                            getPage(multiNewsWorkaround);
                        } else if (!StringUtils.endsWithCaseInsensitive(page, redirect)) {
                            final String waitStr = br.getRegex("content=\"(\\d+)").getMatch(0);
                            int wait = 10;
                            if (waitStr != null) {
                                wait = Integer.parseInt(waitStr);
                            }
                            Thread.sleep(wait * 1001l);
                            getPage(redirect);
                        } else {
                            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                        }
                    }
                }
            }
        } finally {
            if (workaround) {
                multiNewsWorkaround = null;
            }
        }
    }

    private String getFileSize(String parameter) throws Exception {
        if (br.getRequest() == null) {
            getPage(parameter);
        }
        return getWebsiteFileSize(br);
    }

    private String getFilename(String parameter) throws Exception {
        String filename = new Regex(parameter, "/[0-9a-f]{32}(?:/|_)([^/]+)").getMatch(0);
        if (filename == null) {
            // here it can be present within html source
            getPage(parameter);
            return getWebsiteFileName(br);
        }
        return filename;
    }

    private String getWebsiteFileSize(Browser br) {
        String fileSize = br.getRegex("Size\\s*:\\s*([0-9\\.]+\\s*[GMK]iB)\\s*<br").getMatch(0);
        if (fileSize == null) {
            fileSize = br.getRegex("\"description\"\\s*content\\s*=\\s*\"\\s*(?:Mirror\\s*list|Download)\\s*.*?\\s*\\(([0-9\\.]+\\s*[GMK]iB)\\)").getMatch(0);
        }
        return fileSize;
    }

    private String getWebsiteFileName(Browser br) {
        String fileName = br.getRegex("<title>\\s*Download\\s*(.*?)\\s*-\\sMirror").getMatch(0);
        if (fileName == null) {
            fileName = br.getRegex("<meta name=\"description\" content=\"Download\\s*(.*?)\\s*\\(").getMatch(0);
            if (fileName == null) {
                fileName = br.getRegex("<title>\\s*Mirror list\\s*(.*?)\\s*-\\sMirror").getMatch(0);
            }
        }
        return fileName;
    }

    private String getFUID(final String url) {
        final String fuid = new Regex(url, "(?:_|/)([a-f0-9]{32})").getMatch(0);
        return fuid;
    }

    @Override
    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        return false;
    }
}