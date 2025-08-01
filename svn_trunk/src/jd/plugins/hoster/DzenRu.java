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
package jd.plugins.hoster;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jd.PluginWrapper;
import jd.parser.Regex;
import jd.plugins.Account;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginBrowser;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

import org.appwork.storage.TypeRef;
import org.appwork.utils.StringUtils;
import org.jdownloader.downloader.hls.HLSDownloader;
import org.jdownloader.plugins.components.hls.HlsContainer;
import org.jdownloader.plugins.controller.LazyPlugin;
import org.jdownloader.scripting.JavaScriptEngineFactory;

@HostPlugin(revision = "$Revision: 51211 $", interfaceVersion = 3, names = {}, urls = {})
public class DzenRu extends PluginForHost {
    public DzenRu(PluginWrapper wrapper) {
        super(wrapper);
    }

    private final String PROPERTY_HLS_MASTER = "hls_master";

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.VIDEO_STREAMING };
    }

    @Override
    public String getAGBLink() {
        return "https://dzen.ru/";
    }

    private static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForHost, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "dzen.ru", "zen.yandex.ru" });
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
        final List<String> ret = new ArrayList<String>();
        for (final String[] domains : getPluginDomains()) {
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/video/watch/([a-f0-9]{24})");
        }
        return ret.toArray(new String[0]);
    }

    @Override
    public String getLinkID(final DownloadLink link) {
        final String fid = getFID(link);
        if (fid != null) {
            return this.getHost() + "://" + fid;
        } else {
            return super.getLinkID(link);
        }
    }

    private String getFID(final DownloadLink link) {
        return new Regex(link.getPluginPatternMatcher(), this.getSupportedLinks()).getMatch(0);
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        link.removeProperty(PROPERTY_HLS_MASTER);
        final String extDefault = ".mp4";
        final String videoid = this.getFID(link);
        if (!link.isNameSet()) {
            /* Fallback */
            link.setName(videoid + extDefault);
        }
        this.setBrowserExclusive();
        br.setFollowRedirects(true);
        br.getPage(link.getPluginPatternMatcher());
        // br.getPage(link.getPluginPatternMatcher());
        final String redirectJson = br.getRegex("var it = (\\{.*\\});").getMatch(0);
        if (redirectJson != null) {
            logger.info("Handling redirect");
            final Map<String, Object> entries = restoreFromString(redirectJson, TypeRef.MAP);
            br.getPage(entries.get("retpath").toString());
        }
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        String[] jsons = br.getRegex("_params=\\((\\{.*?\\})\\);").getColumn(0);
        if (jsons == null || jsons.length == 0) {
            logger.warning("Failed to find any json items");
        }
        Map<String, Object> videomap = null;
        Map<String, Object> videoinfomap = null;
        int jsonindex = -1;
        for (final String json : jsons) {
            jsonindex++;
            Object entries = null;
            try {
                entries = restoreFromString(json, TypeRef.OBJECT);
            } catch (final Throwable e) {
                /* 2024-06-10: This is the dontcare way^^ */
                logger.info("Skipping invalid json | index: " + jsonindex + " | json: " + json);
                continue;
            }
            if (videomap == null) {
                videomap = (Map<String, Object>) findVideoMapRecursive(entries, videoid);
            }
            if (videoinfomap == null) {
                videoinfomap = (Map<String, Object>) findVideoInformationMapRecursive(entries, videoid);
            }
            if (videomap != null && videoinfomap != null) {
                break;
            }
        }
        if (videomap == null) {
            /* This will cause NPE down below */
            logger.warning("Failed to find videomap");
        }
        String dateFormatted = null;
        String title = (String) JavaScriptEngineFactory.walkJson(videomap, "rawStreams/SingleStream/{0}/Title");
        if (title == null && videoinfomap != null) {
            title = (String) videoinfomap.get("title");
        }
        String description = (String) videomap.get("description");
        if (description == null && videoinfomap != null) {
            description = (String) videoinfomap.get("description");
        }
        String hlsMaster = null;
        final List<String> streams = (List<String>) videomap.get("streams");
        for (final String stream : streams) {
            if (stream.contains(".m3u8")) {
                hlsMaster = stream;
                break;
            }
        }
        if (hlsMaster != null) {
            link.setProperty(PROPERTY_HLS_MASTER, hlsMaster);
        }
        /* Scan for more metadata */
        final Map<String, Object> schemaOrgVideoObject = ((PluginBrowser) br).getVideoObject();
        if (schemaOrgVideoObject != null) {
            if (StringUtils.isEmpty(title)) {
                title = (String) schemaOrgVideoObject.get("name");
            }
            if (StringUtils.isEmpty(dateFormatted)) {
                dateFormatted = (String) schemaOrgVideoObject.get("uploadDate");
            }
            if (StringUtils.isEmpty(description)) {
                description = (String) schemaOrgVideoObject.get("description");
            }
        }
        if (!StringUtils.isEmpty(description) && StringUtils.isEmpty(link.getComment())) {
            link.setComment(description);
        }
        if (!StringUtils.isEmpty(dateFormatted) && !StringUtils.isEmpty(title)) {
            link.setFinalFileName(dateFormatted + "_" + title + extDefault);
        } else if (!StringUtils.isEmpty(title)) {
            link.setFinalFileName(title + extDefault);
        }
        final Map<String, Object> bestQualityInfo = (Map<String, Object>) videomap.get("bestQualityInfo");
        if (bestQualityInfo != null) {
            /* Accurate filesize source */
            final Object sizeInBytesO = bestQualityInfo.get("sizeInBytes");
            link.setDownloadSize(Long.parseLong(sizeInBytesO.toString()));
        } else {
            /* Inaccurate file size */
            /**
             * Very cheap way of calculating rough filesize using bandwidth of 1080p version and duration of the video. </br> (And yes, not
             * all videos are available in 1080p and real bandwidth may vary.)
             */
            final Number durationSeconds = (Number) videomap.get("duration");
            if (durationSeconds != null) {
                link.setDownloadSize(durationSeconds.longValue() * 1405854 / 8);
            }
        }
        return AvailableStatus.TRUE;
    }

    private Object findVideoMapRecursive(final Object o, final String videoid) {
        if (videoid == null) {
            return null;
        }
        if (o instanceof Map) {
            final Map<String, Object> entrymap = (Map<String, Object>) o;
            for (final Map.Entry<String, Object> entry : entrymap.entrySet()) {
                // final String key = entry.getKey();
                final Object value = entry.getValue();
                if (entrymap.containsKey("isShortVideo")) {
                    return entrymap;
                } else if (value instanceof List || value instanceof Map) {
                    final Object ret = findVideoMapRecursive(value, videoid);
                    if (ret != null) {
                        return ret;
                    }
                }
            }
            return null;
        } else if (o instanceof List) {
            final List<Object> array = (List) o;
            for (final Object arrayo : array) {
                if (arrayo instanceof List || arrayo instanceof Map) {
                    final Object res = findVideoMapRecursive(arrayo, videoid);
                    if (res != null) {
                        return res;
                    }
                }
            }
            return null;
        } else {
            return null;
        }
    }

    private Object findVideoInformationMapRecursive(final Object o, final String videoid) {
        if (videoid == null) {
            return null;
        }
        if (o instanceof Map) {
            final Map<String, Object> entrymap = (Map<String, Object>) o;
            for (final Map.Entry<String, Object> entry : entrymap.entrySet()) {
                // final String key = entry.getKey();
                final Object value = entry.getValue();
                if (entrymap.containsKey("channelOwnerUid") && entrymap.containsKey("video")) {
                    return entrymap;
                } else if (value instanceof List || value instanceof Map) {
                    final Object ret = findVideoInformationMapRecursive(value, videoid);
                    if (ret != null) {
                        return ret;
                    }
                }
            }
            return null;
        } else if (o instanceof List) {
            final List<Object> array = (List) o;
            for (final Object arrayo : array) {
                if (arrayo instanceof List || arrayo instanceof Map) {
                    final Object res = findVideoInformationMapRecursive(arrayo, videoid);
                    if (res != null) {
                        return res;
                    }
                }
            }
            return null;
        } else {
            return null;
        }
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        handleDownload(link, null);
    }

    private void handleDownload(final DownloadLink link, final Account account) throws Exception, PluginException {
        requestFileInformation(link);
        final String hlsMaster = link.getStringProperty(PROPERTY_HLS_MASTER);
        if (StringUtils.isEmpty(hlsMaster)) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        br.getPage(hlsMaster);
        final List<HlsContainer> hlsQualities = HlsContainer.getHlsQualities(br);
        if (hlsQualities == null || hlsQualities.isEmpty()) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "HLS stream broken?");
        }
        final HlsContainer best = HlsContainer.findBestVideoByBandwidth(hlsQualities);
        checkFFmpeg(link, "Download a HLS Stream");
        dl = new HLSDownloader(link, br, best.getDownloadurl());
        dl.startDownload();
    }

    @Override
    public boolean hasCaptcha(DownloadLink link, jd.plugins.Account acc) {
        return false;
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}