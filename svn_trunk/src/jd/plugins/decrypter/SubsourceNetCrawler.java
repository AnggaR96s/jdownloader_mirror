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
import java.util.Map;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.http.requests.GetRequest;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;

import org.appwork.storage.TypeRef;
import org.appwork.utils.StringUtils;

@DecrypterPlugin(revision = "$Revision: 51215 $", interfaceVersion = 3, names = {}, urls = {})
public class SubsourceNetCrawler extends PluginForDecrypt {
    public SubsourceNetCrawler(PluginWrapper wrapper) {
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
        ret.add(new String[] { "subsource.net" });
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
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/subtitles/([\\w\\-]+)");
        }
        return ret.toArray(new String[0]);
    }

    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        final String titleSlug = new Regex(param.getCryptedUrl(), this.getSupportedLinks()).getMatch(0);
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        final GetRequest request = new GetRequest("https://api.subsource.net/v1/subtitles/" + titleSlug);
        request.getHeaders().put("Origin", "https://" + getHost());
        request.getHeaders().put("Priority", "u=1, i");
        request.getHeaders().put("Referer", "https://" + getHost() + "/");
        br.getPage(request);
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final Map<String, Object> entries = restoreFromString(br.getRequest().getHtmlCode(), TypeRef.MAP);
        final Map<String, Object> movie = (Map<String, Object>) entries.get("movie");
        final List<Map<String, Object>> subs = (List<Map<String, Object>>) entries.get("subtitles");
        final String title = movie.get("title").toString();
        final FilePackage fp = FilePackage.getInstance();
        fp.setName(title);
        final HashSet<String> dupes = new HashSet<String>();
        for (final Map<String, Object> sub : subs) {
            if (!sub.containsKey("id")) {
                continue;
            }
            final String subID = sub.get("id").toString();
            if (!dupes.add(subID)) {
                /* Skip dupes */
                continue;
            }
            String link = sub.get("link").toString();
            if (!StringUtils.startsWithCaseInsensitive(link, "https://")) {
                link = "https://" + getHost() + "/subtitle" + (link.startsWith("/") ? link : "/" + link);
            }
            final DownloadLink downloadLink = this.createDownloadlink(link);
            downloadLink.setName(titleSlug + "_" + sub.get("language") + "_" + subID + ".zip");
            downloadLink.setAvailable(true);
            downloadLink._setFilePackage(fp);
            ret.add(downloadLink);
        }
        return ret;
    }

    @Override
    public int getMaxConcurrentProcessingInstances() {
        /* Try to avoid running into rate-limit */
        return 1;
    }
}
