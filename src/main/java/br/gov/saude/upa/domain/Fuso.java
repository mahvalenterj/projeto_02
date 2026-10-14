package br.gov.saude.upa.domain;

import java.time.ZoneId;

/** Fuso único das regras de "dia" e "horário" do domínio (spec 001). */
interface Fuso {

  ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
}
