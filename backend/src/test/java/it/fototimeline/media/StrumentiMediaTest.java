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
}
