package it.fototimeline.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import it.fototimeline.cloud.ArchivioNonDisponibile;
import it.fototimeline.cloud.RcloneNonRiesce;

@RestControllerAdvice
public class GestoreErrori {

    @ExceptionHandler(ArchivioNonDisponibile.class)
    public ProblemDetail archivioSmontato(ArchivioNonDisponibile e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(RcloneNonRiesce.class)
    public ProblemDetail rclone(RcloneNonRiesce e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail statoNonValido(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail richiestaNonValida(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
