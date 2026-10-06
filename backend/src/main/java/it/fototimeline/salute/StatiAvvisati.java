package it.fototimeline.salute;

import java.util.Map;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Lo stato di ogni voce all'ultimo controllo degli avvisi: si avvisa solo
 * quando cambia, anche dopo un riavvio.
 */
@Document("impostazioni")
record StatiAvvisati(@Id String id, Map<String, StatoSalute> stati) {

    static final String ID = "avvisi-salute";
}
