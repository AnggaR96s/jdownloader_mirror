package org.jdownloader.plugins.components.hls;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import jd.http.Browser;
import jd.plugins.DownloadLink;
import jd.plugins.hoster.GenericM3u8;

import org.appwork.utils.Regex;
import org.appwork.utils.StringUtils;
import org.appwork.utils.logging2.LogInterface;
import org.jdownloader.downloader.hls.M3U8Playlist;
import org.jdownloader.logging.LogController;

public class HlsContainer {
    public static List<HlsContainer> findBestVideosByBandwidth(final List<HlsContainer> media) {
        if (media == null || media.size() == 0) {
            return null;
        }
        final Map<String, List<HlsContainer>> hlsContainer = new HashMap<String, List<HlsContainer>>();
        List<HlsContainer> ret = null;
        long bandwidth_highest = 0;
        for (HlsContainer item : media) {
            final String id = item.getExtXStreamInf();
            List<HlsContainer> list = hlsContainer.get(id);
            if (list == null) {
                list = new ArrayList<HlsContainer>();
                hlsContainer.put(id, list);
            }
            list.add(item);
            long bandwidth_temp = item.getBandwidth();
            if (bandwidth_temp == -1) {
                bandwidth_temp = item.getAverageBandwidth();
            }
            if (bandwidth_temp > bandwidth_highest) {
                bandwidth_highest = bandwidth_temp;
                ret = list;
            }
        }
        return ret;
    }

    public static HlsContainer findBestTargetHeight(final List<HlsContainer> media, final int targetHeight) {
        if (media == null || media.size() == 0) {
            return null;
        }
        // Find next best quality >= targetHeight
        HlsContainer best = null;
        for (HlsContainer next : media) {
            if (next.getHeight() >= targetHeight) {
                if (best == null) {
                    // first quality >= tartgetHeight
                    best = next;
                } else if (Math.abs(next.getHeight() - targetHeight) < Math.abs(best.getHeight() - targetHeight)) {
                    // next has smaller distance to targetHeight than best
                    best = next;
                } else if (Math.abs(next.getHeight() - targetHeight) == Math.abs(best.getHeight() - targetHeight)) {
                    // next has same distance to targetHeight as best, now choose best bandwidth;
                    final int nextBW = Math.max(next.getBandwidth(), next.getAverageBandwidth());
                    final int bestBW = Math.max(best.getBandwidth(), best.getAverageBandwidth());
                    if (nextBW > bestBW) {
                        best = next;
                    }
                }
            }
        }
        if (best != null) {
            return best;
        }
        // If no higher quality found, return the highest available (should be <targetHeight)
        return HlsContainer.findBestVideoByBandwidth(media);
    }

    public static HlsContainer findBestVideoByBandwidth(final List<HlsContainer> media) {
        final List<HlsContainer> ret = findBestVideosByBandwidth(media);
        if (ret != null && ret.size() > 0) {
            return ret.get(0);
        } else {
            return null;
        }
    }

    public static List<HlsContainer> getHlsQualities(final Browser br, final String m3u8) throws Exception {
        br.getHeaders().put("Accept", "*/*");
        br.getPage(m3u8);
        return getHlsQualities(br);
    }

    public static List<HlsContainer> getHlsQualities(final Browser br) throws Exception {
        return parseHlsQualities(br.toString(), br);
    }

    public static List<HlsContainer> parseHlsQualities(final String m3u8, final Browser br) throws Exception {
        final ArrayList<HlsContainer> hlsqualities = new ArrayList<HlsContainer>();
        // TODO: update to support #EXT-X-SESSION-DATA:DATA-ID="com.example.title",LANGUAGE="en", VALUE="This is an example",see
        // GenericM3u8Decrypter
        // https://hlsbook.net/adding-session-data-to-a-playlist/
        final String[][] streams = new Regex(m3u8, "#EXT-X-STREAM-INF:?([^\r\n]+)[\r\n]+([^\r\n]+)").getMatches();
        if (streams != null) {
            for (final String stream[] : streams) {
                if (StringUtils.isNotEmpty(stream[1])) {
                    final String streamInfo = stream[0];
                    if (false && new Regex(streamInfo, "(?:,|^)\\s*AUDIO\\s*=").matches()) {
                        LogInterface logger = br.getLogger();
                        if (logger == null) {
                            logger = LogController.CL();
                        }
                        logger.info("Unsupported M3U8! Split Audio/Video, see  https://svn.jdownloader.org/issues/87898|" + streamInfo);
                        continue;
                    }
                    // final String quality = new Regex(media, "(?:,|^)\\s*NAME\\s*=\\s*\"(.*?)\"").getMatch(0);
                    final String programID = new Regex(streamInfo, "(?:,|^)\\s*PROGRAM-ID\\s*=\\s*(\\d+)").getMatch(0);
                    final String bandwidth = new Regex(streamInfo, "(?:,|^)\\s*BANDWIDTH\\s*=\\s*(\\d+)").getMatch(0);
                    final String average_bandwidth = new Regex(streamInfo, "(?:,|^)\\s*AVERAGE-BANDWIDTH\\s*=\\s*(\\d+)").getMatch(0);
                    final String resolution = new Regex(streamInfo, "(?:,|^)\\s*RESOLUTION\\s*=\\s*(\\d+x\\d+)").getMatch(0);
                    final String framerate = new Regex(streamInfo, "(?:,|^)\\s*FRAME-RATE\\s*=\\s*(\\d+)").getMatch(0);
                    final String codecs = new Regex(streamInfo, "(?:,|^)\\s*CODECS\\s*=\\s*\"([^<>\"]+)\"").getMatch(0);
                    final String name = new Regex(streamInfo, "(?:,|^)\\s*NAME\\s*=\\s*\"([^<>\"]+)\"").getMatch(0);
                    // final String uri = new Regex(streamInfo, "(?:,|^)\\s*URI\\s*=\\s*\"([^<>\"]+)\"").getMatch(0);
                    // final String language = new Regex(streamInfo, "(?:,|^)\\s*LANGUAGE\\s*=\\s*\"([^<>\"]+)\"").getMatch(0);
                    // final String type = new Regex(streamInfo, "(?:,|^)\\s*TYPE\\s*=\\s*([^<>\"]+)").getMatch(0);
                    final String url = br.getURL(stream[1]).toString();
                    final HlsContainer hls = new HlsContainer();
                    if (programID != null) {
                        hls.programID = Integer.parseInt(programID);
                    } else {
                        hls.programID = -1;
                    }
                    if (bandwidth != null) {
                        hls.bandwidth = Integer.parseInt(bandwidth);
                    } else {
                        hls.bandwidth = -1;
                    }
                    if (name != null) {
                        hls.name = name.trim();
                    }
                    if (average_bandwidth != null) {
                        hls.average_bandwidth = Integer.parseInt(average_bandwidth);
                    } else {
                        hls.average_bandwidth = -1;
                    }
                    if (codecs != null) {
                        hls.codecs = codecs.trim();
                    }
                    hls.streamURL = url;
                    hls.m3u8URL = br.getURL();
                    if (resolution != null) {
                        final String[] resolution_info = resolution.split("x");
                        final String width = resolution_info[0];
                        final String height = resolution_info[1];
                        hls.width = Integer.parseInt(width);
                        hls.height = Integer.parseInt(height);
                    }
                    if (framerate != null) {
                        hls.framerate = Integer.parseInt(framerate);
                    }
                    hlsqualities.add(hls);
                }
            }
        }
        return hlsqualities;
    }

    private String codecs;
    private String streamURL;
    private String m3u8URL;

    public String getM3U8URL() {
        return m3u8URL;
    }

    private List<M3U8Playlist> m3u8List = null;
    private int                width    = -1;

    public void setAverageBandwidth(int average_bandwidth) {
        this.average_bandwidth = average_bandwidth;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public void setHeight(int height) {
        this.height = height;
    }

    public void setBandwidth(int bandwidth) {
        this.bandwidth = bandwidth;
    }

    private int height            = -1;
    private int bandwidth         = -1;
    private int average_bandwidth = -1;
    private int programID         = -1;
    private int framerate         = -1;

    public void setFramerate(int framerate) {
        this.framerate = framerate;
    }

    private String name = null;

    public String getName() {
        return name;
    }

    protected List<M3U8Playlist> loadM3U8(Browser br) throws IOException {
        final Browser br2 = br.cloneBrowser();
        return M3U8Playlist.loadM3U8(getStreamURL(), br2);
    }

    public void setM3U8(List<M3U8Playlist> m3u8List) {
        this.m3u8List = m3u8List;
    }

    public String getExtXStreamInf() {
        final StringBuilder sb = new StringBuilder();
        sb.append("#EXT-X-STREAM-INF:");
        boolean sep = false;
        if (getProgramID() != -1) {
            sb.append("PROGRAM-ID=" + getProgramID());
            sep = true;
        }
        if (getBandwidth() != -1) {
            if (sep) {
                sb.append(",");
            }
            sb.append("BANDWIDTH=" + getBandwidth());
            sep = true;
        }
        if (getAverageBandwidth() != -1) {
            if (sep) {
                sb.append(",");
            }
            sb.append("AVERAGE-BANDWIDTH=" + getAverageBandwidth());
            sep = true;
        }
        if (getCodecs() != null) {
            if (sep) {
                sb.append(",");
            }
            sb.append("CODECS=\"" + getCodecs() + "\"");
            sep = true;
        }
        if (getResolution() != null) {
            if (sep) {
                sb.append(",");
            }
            sb.append("RESOLUTION=" + getResolution());
            sep = true;
        }
        if (getFramerate() != -1) {
            if (sep) {
                sb.append(",");
            }
            sb.append("FRAME-RATE=" + getFramerate());
            sep = true;
        }
        if (getName() != null) {
            if (sep) {
                sb.append(",");
            }
            sb.append("NAME=\"" + getName() + "\"");
            sep = true;
        }
        return sb.toString();
    }

    public List<M3U8Playlist> getM3U8(final Browser br) throws IOException {
        if (m3u8List == null) {
            setM3U8(loadM3U8(br));
            int bandwidth = getAverageBandwidth();
            if (bandwidth < 0) {
                bandwidth = getBandwidth();
            }
            if (m3u8List != null && bandwidth > 0) {
                for (final M3U8Playlist m3u8 : m3u8List) {
                    m3u8.setAverageBandwidth(bandwidth);
                }
            }
        }
        return m3u8List;
    }

    public int getProgramID() {
        return programID;
    }

    public static enum CODEC_TYPE {
        VIDEO,
        AUDIO,
        UNKNOWN
    }

    public static enum CODEC {
        // http://mp4ra.org/#/codecs
        // https://wiki.multimedia.cx/index.php/MPEG-4_Audio#Audio_Object_Types
        // https://developer.apple.com/documentation/http_live_streaming/http_live_streaming_hls_authoring_specification_for_apple_devices/hls_authoring_specification_for_apple_devices_appendixes
        // https://www.loc.gov/preservation/digital/formats/fdd/fdd000105.shtml
        MP3(CODEC_TYPE.AUDIO, "mp3,", "mp3", "(mp4a\\.40\\.34|mp3)"),
        // unofficial mpeg-1/mpeg-2 audio layer 2, https://www.loc.gov/preservation/digital/formats/fdd/fdd000338.shtml
        MP2(CODEC_TYPE.AUDIO, "mp2", "mp2", "mp2"),
        AAC(CODEC_TYPE.AUDIO, "aac", "m4a", "(mp4a\\.40|aac)"),
        AC3(CODEC_TYPE.AUDIO, "ac3", "ac3", "(ac-3|ac3)"), // AC-3 (Dolby Digital), up to 5.1
        EC3(CODEC_TYPE.AUDIO, "ec3", "ec3", "(ec-3|ec3)"), // EC-3 (Dolby Digital Plus) up to 15.1
        FLAC(CODEC_TYPE.VIDEO, "flac", "flac", "fLaC"),
        AVC(CODEC_TYPE.VIDEO, "avc", "mp4", "(avc1|avc3|h264)"),
        HEVC(CODEC_TYPE.VIDEO, "hevc", "mp4", "(hev1|hvc1|h265)"),
        UNKNOWN(CODEC_TYPE.UNKNOWN, null, null, null);

        private final CODEC_TYPE type;

        public CODEC_TYPE getType() {
            return type;
        }

        public String getDefaultExtension() {
            return defaultExtension;
        }

        public Pattern getPattern() {
            return pattern;
        }

        private final String  defaultExtension;
        private final Pattern pattern;
        private final String  codecName;

        public String getCodecName() {
            return codecName;
        }

        private CODEC(CODEC_TYPE type, final String codecName, final String defaultExtension, final String pattern) {
            this.type = type;
            this.codecName = codecName;
            this.defaultExtension = defaultExtension;
            this.pattern = pattern != null ? Pattern.compile(pattern) : null;
        }

        public static CODEC parse(final String raw) {
            if (StringUtils.isNotEmpty(raw)) {
                for (CODEC codec : values()) {
                    if (codec.getPattern() != null && new Regex(raw, codec.getPattern()).matches()) {
                        return codec;
                    }
                }
            }
            return UNKNOWN;
        }
    }

    public static class StreamCodec {
        private final CODEC codec;

        public CODEC getCodec() {
            return codec;
        }

        public String getRaw() {
            return raw;
        }

        private final String raw;

        private StreamCodec(final String raw) {
            this.raw = raw;
            this.codec = CODEC.parse(raw);
        }

        public static List<StreamCodec> parse(final String raw) {
            final String[] codecs = raw != null ? raw.split(",") : null;
            if (codecs != null) {
                final List<StreamCodec> ret = new ArrayList<StreamCodec>();
                for (final String codec : codecs) {
                    ret.add(new StreamCodec(codec));
                }
                return ret;
            } else {
                return null;
            }
        }
    }

    public List<StreamCodec> getStreamCodecs() {
        return StreamCodec.parse(getCodecs());
    }

    public StreamCodec getCodecType(CODEC_TYPE type) {
        final List<StreamCodec> ret = getStreamCodecs();
        if (ret != null) {
            for (final StreamCodec streamCodec : ret) {
                if (streamCodec.getCodec().getType().equals(type)) {
                    return streamCodec;
                }
            }
        }
        return null;
    }

    public StreamCodec getCodec(CODEC codec) {
        final List<StreamCodec> ret = getStreamCodecs();
        if (ret != null) {
            for (final StreamCodec streamCodec : ret) {
                if (streamCodec.getCodec().equals(codec)) {
                    return streamCodec;
                }
            }
        }
        return null;
    }

    /** Returns un-parsed codecs string. */
    public String getCodecs() {
        return this.codecs;
    }

    public void setStreamURL(final String url) {
        this.streamURL = url;
    }

    @Deprecated
    public String getDownloadurl() {
        return getStreamURL();
    }

    public String getStreamURL() {
        return streamURL;
    }

    public boolean isVideo() {
        if (getCodecType(CODEC_TYPE.VIDEO) != null) {
            return true;
        } else if (this.width == -1 && this.height == -1) {
            /* Audio/subtitle */
            return false;
        } else {
            return true;
        }
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    public int getFramerate() {
        return framerate;
    }

    /**
     * @param fallback
     *            : Value to be returned if framerate is unknown - usually this will be 25.
     */
    public int getFramerate(final int fallback) {
        if (framerate == -1) {
            return fallback;
        } else {
            return framerate;
        }
    }

    /** Returns video resulution as string <width>x<height> e.g. 1920x1080. */
    public String getResolution() {
        return this.getWidth() + "x" + this.getHeight();
    }

    public int getBandwidth() {
        return this.bandwidth;
    }

    public int getAverageBandwidth() {
        return this.average_bandwidth;
    }

    public HlsContainer() {
    }

    @Override
    public String toString() {
        return getExtXStreamInf();
    }

    public String getStandardFilename() {
        String filename = "";
        if (width != -1 && height != -1) {
            filename += getResolution();
        }
        if (codecs != null) {
            filename += "_" + codecs;
        }
        filename += getFileExtension();
        return filename;
    }

    public String getFileExtension(final String fallback) {
        final StreamCodec video = getCodecType(CODEC_TYPE.VIDEO);
        final StreamCodec audio = getCodecType(CODEC_TYPE.AUDIO);
        if (video != null) {
            return "." + video.getCodec().getDefaultExtension();
        } else if (audio != null) {
            return "." + audio.getCodec().getDefaultExtension();
        } else {
            // fallback
            return fallback;
        }
    }

    @Deprecated
    public String getFileExtension() {
        return getFileExtension(".mp4");
    }

    public void setPropertiesOnDownloadLink(final DownloadLink link) {
        if (this.getWidth() > 0) {
            link.setProperty(GenericM3u8.PROPERTY_WIDTH, this.getWidth());
        }
        if (this.getHeight() > 0) {
            link.setProperty(GenericM3u8.PROPERTY_HEIGHT, this.getHeight());
        }
        if (this.getFramerate() > 0) {
            link.setProperty(GenericM3u8.PROPERTY_FRAME_RATE, this.getFramerate());
        }
        if (this.getBandwidth() > 0) {
            link.setProperty(GenericM3u8.PROPERTY_BANDWIDTH, this.getBandwidth());
        }
        if (this.getAverageBandwidth() > 0) {
            link.setProperty(GenericM3u8.PROPERTY_BANDWIDTH_AVERAGE, this.getAverageBandwidth());
        }
        link.setProperty(GenericM3u8.PROPERTY_M3U8_NAME, this.getName());
        link.setProperty(GenericM3u8.PROPERTY_M3U8_CODECS, this.getCodecs());
        // TODO: Set type of content e.g. audio, video, subtitle
    }
}