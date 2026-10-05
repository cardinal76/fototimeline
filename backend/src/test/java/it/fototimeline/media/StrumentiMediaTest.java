package it.fototimeline.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Come si leggono le risposte di ffprobe: senza ffprobe, su JSON di esempio. */
class StrumentiMediaTest {

    private static final ZoneId ROMA = ZoneId.of("Europe/Rome");

    private static InfoVideo leggi(String json) throws Exception {
        return StrumentiMedia.interpreta(new ObjectMapper().readTree(json), ROMA);
    }

    @Test
    void iPhoneVerticaleConDataLocaleEPosizione() throws Exception {
        var info = leggi("""
                {"streams":[{"codec_type":"audio"},
                            {"codec_type":"video","width":1920,"height":1080,
                             "side_data_list":[{"side_data_type":"Display Matrix","rotation":-90}]}],
                 "format":{"duration":"12.5","tags":{
                    "creation_time":"2023-07-14T19:05:00.000000Z",
                    "com.apple.quicktime.creationdate":"2023-07-14T21:05:00+0200",
                    "com.apple.quicktime.make":"Apple","com.apple.quicktime.model":"iPhone 15",
                    "com.apple.quicktime.location.ISO6709":"+41.9028+012.4964+050.000/"}}}""");

        assertThat(info.larghezza()).isEqualTo(1080);
        assertThat(info.altezza()).isEqualTo(1920);
        assertThat(info.durata()).isEqualTo(12.5);
        assertThat(info.ripresoIl()).isEqualTo(LocalDateTime.of(2023, 7, 14, 21, 5));
        assertThat(info.fotocamera()).isEqualTo("Apple iPhone 15");
        assertThat(info.latitudine()).isEqualTo(41.9028);
        assertThat(info.longitudine()).isEqualTo(12.4964);
    }

    @Test
    void dataUtcConvertitaNelFusoEDate1970Ignorate() throws Exception {
        var android = leggi("""
                {"streams":[{"codec_type":"video","width":640,"height":480,"tags":{"rotate":"0"}}],
                 "format":{"tags":{"creation_time":"2021-05-01T10:20:30.000000Z","location":"-33.8688+151.2093/"}}}""");
        assertThat(android.ripresoIl()).isEqualTo(LocalDateTime.of(2021, 5, 1, 12, 20, 30));
        assertThat(android.larghezza()).isEqualTo(640);
        assertThat(android.latitudine()).isEqualTo(-33.8688);

        var senzaData = leggi("""
                {"streams":[{"codec_type":"video","width":640,"height":480}],
                 "format":{"tags":{"creation_time":"1970-01-01T00:00:00.000000Z"}}}""");
        assertThat(senzaData.ripresoIl()).isNull();
    }

    @Test
    void unFileSenzaVideoNonEUnVideo() {
        assertThatThrownBy(() -> leggi("{\"streams\":[{\"codec_type\":\"audio\"}],\"format\":{}}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hevcDelliPhoneNonECompatibile() throws Exception {
        var info = leggi("""
                {"streams":[{"codec_type":"video","codec_name":"hevc","pix_fmt":"yuv420p10le","width":3840,"height":2160},
                            {"codec_type":"audio","codec_name":"aac"}],
                 "format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2"}}""");
        assertThat(info.codecVideo()).isEqualTo("hevc");
        assertThat(info.codecAudio()).isEqualTo("aac");
        assertThat(info.compatibile()).isFalse();
    }

    @Test
    void h264ConAacOMutoECompatibile() throws Exception {
        var conAudio = leggi("""
                {"streams":[{"codec_type":"video","codec_name":"h264","pix_fmt":"yuv420p","width":1920,"height":1080},
                            {"codec_type":"audio","codec_name":"aac"}],
                 "format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2"}}""");
        assertThat(conAudio.compatibile()).isTrue();
        var muto = leggi("""
                {"streams":[{"codec_type":"video","codec_name":"h264","pix_fmt":"yuvj420p","width":640,"height":480}],
                 "format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2"}}""");
        assertThat(muto.compatibile()).isTrue();
        assertThat(muto.codecAudio()).isNull();
    }

    @Test
    void h264A10BitAudioPcmVp9EProResVoglionoLaConversione() {
        String mp4 = "mov,mp4,m4a,3gp,3g2,mj2";
        assertThat(StrumentiMedia.compatibile(mp4, "h264", "yuv420p10le", "aac")).isFalse();
        assertThat(StrumentiMedia.compatibile(mp4, "h264", "yuv420p", "pcm_s16le")).isFalse();
        assertThat(StrumentiMedia.compatibile(mp4, "vp9", "yuv420p", "opus")).isFalse();
        assertThat(StrumentiMedia.compatibile(mp4, "prores", "yuv422p10le", "pcm_s24le")).isFalse();
        assertThat(StrumentiMedia.compatibile("matroska,webm", "h264", "yuv420p", "aac")).isFalse();
        assertThat(StrumentiMedia.compatibile(mp4, "h264", "yuv420p", "mp3")).isTrue();
    }

    @Test
    void laCopertinaNonEIlVideo() throws Exception {
        // Alcuni MP4 hanno una copertina JPEG come flusso "video" prima di quello vero.
        var info = leggi("""
                {"streams":[{"codec_type":"video","codec_name":"mjpeg","disposition":{"attached_pic":1}},
                            {"codec_type":"video","codec_name":"h264","pix_fmt":"yuv420p","width":640,"height":360}],
                 "format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2"}}""");
        assertThat(info.codecVideo()).isEqualTo("h264");
        assertThat(info.larghezza()).isEqualTo(640);
    }

    @Test
    void ilLatoCortoScendeAlMassimo() {
        assertThat(StrumentiMedia.scala(1080))
                .isEqualTo("scale=w='if(gt(iw,ih),-2,trunc(min(iw,1080)/2)*2)':h='if(gt(iw,ih),trunc(min(ih,1080)/2)*2,-2)'");
    }
}
