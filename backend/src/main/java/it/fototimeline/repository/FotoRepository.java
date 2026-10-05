package it.fototimeline.repository;

import org.springframework.data.mongodb.repository.MongoRepository;

import it.fototimeline.dominio.Foto;

public interface FotoRepository extends MongoRepository<Foto, String> {

    boolean existsByHash(String hash);

    java.util.Optional<Foto> findByHash(String hash);

    boolean existsByPercorso(String percorso);
}
