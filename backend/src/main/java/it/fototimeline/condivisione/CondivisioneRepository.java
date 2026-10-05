package it.fototimeline.condivisione;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface CondivisioneRepository extends MongoRepository<Condivisione, String> {

    Optional<Condivisione> findByToken(String token);

    List<Condivisione> findByCreataDaOrderByCreataIlDesc(String creataDa);

    List<Condivisione> findAllByOrderByCreataIlDesc();
}
