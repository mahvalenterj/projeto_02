package br.gov.saude.upa.application;

import java.time.Duration;
import java.time.Instant;

/**
 * Porta de saída do despachante. As operações de fim usam {@code tentativas} como senha da
 * reivindicação: se outro despachante reivindicou depois (lease expirado), a operação atrasada não
 * tem efeito e devolve {@code false}.
 */
public interface FilaDoOutbox {

  /**
   * Reivindica até {@code limite} eventos pendentes cujo lease/atraso já passou, com exclusividade
   * entre despachantes, e conta uma tentativa em cada. Uma linha com payload ilegível não trava as
   * demais: é marcada como esgotada e devolvida em {@link Reivindicacao#ilegiveis()}.
   */
  Reivindicacao reivindicar(Instant agora, Duration lease, int limite);

  boolean marcarProcessado(EventoReivindicado reivindicado, Instant agora);

  /** Devolve à fila; só volta a ser reivindicado a partir de {@code reentregarEm}. */
  boolean devolver(EventoReivindicado reivindicado, Instant reentregarEm, String tipoDoErro);

  boolean esgotar(EventoReivindicado reivindicado, String tipoDoErro);
}
