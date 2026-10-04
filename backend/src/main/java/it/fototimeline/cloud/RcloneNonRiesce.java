package it.fototimeline.cloud;

/** rclone ha rifiutato il comando o non risponde. */
public class RcloneNonRiesce extends RuntimeException {

    public RcloneNonRiesce(String messaggio) {
        super(messaggio);
    }
}
