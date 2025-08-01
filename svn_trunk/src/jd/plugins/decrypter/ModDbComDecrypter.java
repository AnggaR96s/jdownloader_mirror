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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.appwork.utils.StringUtils;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.hoster.ModDbCom;

@DecrypterPlugin(revision = "$Revision: 51145 $", interfaceVersion = 2, names = {}, urls = {})
public class ModDbComDecrypter extends PluginForDecrypt {
    public ModDbComDecrypter(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public Browser createNewBrowserInstance() {
        final Browser br = super.createNewBrowserInstance();
        br.setFollowRedirects(true);
        return br;
    }

    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForDecrypt, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "moddb.com" });
        return ret;
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
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/(games|mods|engines|groups)/([\\w+\\-]+)(/(addons|downloads)(/page/\\d+|/[\\w\\-]+)?)?");
        }
        return ret.toArray(new String[0]);
    }

    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        String contenturl = param.getCryptedUrl();
        final Regex urlinforegex = new Regex(contenturl, this.getSupportedLinks());
        final HashSet<String> invalidfileitems = new HashSet<String>();
        invalidfileitems.add("add");
        invalidfileitems.add("feed");
        invalidfileitems.add("page");
        final String singlefileitemregexStr = "(?i).+/(addons|downloads)/([\\w\\-]+)$";
        final Regex singleitemregex = new Regex(contenturl, singlefileitemregexStr);
        final String titleSlug = urlinforegex.getMatch(1);
        final ModDbCom hosterplugin = (ModDbCom) this.getNewPluginForHostInstance(this.getHost());
        if (singleitemregex.patternFind()) {
            /* Single file */
            final String singlefileitemSlug = singleitemregex.getMatch(1);
            if (invalidfileitems.contains(singlefileitemSlug)) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            br.getPage(contenturl);
            hosterplugin.checkErrors(br);
            // Get pages with the mirrors
            hosterplugin.getSinglemirrorpage(br);
            ret.add(createDownloadlink(contenturl.replace("moddb.com/", "moddbdecrypted.com/")));
            return ret;
        } else {
            /* Multiple items */
            if (!StringUtils.containsIgnoreCase(contenturl, "/addons") && !StringUtils.containsIgnoreCase(contenturl, "/downloads")) {
                /* Correct URL added by user */
                contenturl += "/downloads";
            }
            br.getPage(contenturl);
            hosterplugin.checkErrors(br);
            if (br.containsHTML(">\\s*No files were found matching the criteria specified")) {
                /* e.g. /games/nom-nom-apocalypse/downloads and /games/monday-meltdown */
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            final String[] urls = br.getRegex("(/[\\w\\-]+/" + titleSlug + "/(addons|downloads)/(?!feed|page)[\\w\\-]+)").getColumn(0);
            if (urls == null || urls.length == 0) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            final FilePackage fp = FilePackage.getInstance();
            fp.setName(titleSlug.replace("-", " ").trim());
            fp.setPackageKey("moddb://slug/" + titleSlug);
            for (final String downloadlink : urls) {
                final Regex thisSingleitemregex = new Regex(downloadlink, singlefileitemregexStr);
                final String thisTitleSlug = thisSingleitemregex.getMatch(1);
                /* Skip invalid results here already in order to prevent getting ugly offline-items later on. */
                if (invalidfileitems.contains(thisTitleSlug)) {
                    /* Skip invalid items */
                    logger.info("Skipping invalid URL: " + downloadlink);
                    continue;
                }
                final String fullurl = br.getURL(downloadlink).toExternalForm();
                final DownloadLink link = this.createDownloadlink(fullurl);
                link._setFilePackage(fp);
                ret.add(link);
            }
        }
        return ret;
    }

    @Override
    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        return false;
    }
}