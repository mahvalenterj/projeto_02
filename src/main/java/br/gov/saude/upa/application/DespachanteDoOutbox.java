package br.gov.saude.upa.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entrega os eventos do outbox ao caso de uso do lote, pelo menos uma vez. Reivindica na fila
 * (transação curta, com lease) UM evento por vez e processa FORA de qualquer transação, de modo que
 * o lease de cada evento começa quando ele começa. Só marca o evento processado quando o lote
 * termina sem falhas inesperadas; do contrário devolve com atraso (maior que o tempo-limite da
 * reserva, para as reservas já valerem como abandonadas) até o máximo de tentativas, quando esgota
 * e alerta. O lease deve cobrir o processamento de UM evento; se expirar, outro despachante
 * reentrega, e a idempotência do aviso (ADR 0001) evita duplicar. Escrita que perdeu o lease não
 * conta nem alerta.
 */
public class DespachanteDoOutbox {

  private static final Logger LOG = LoggerFactory.getLogger(DespachanteDoOutbox.class);

  static final String FALHAS_INESPERADAS = "FalhasInesperadasNoLote";
  static final String LEASE_EXPIRADO_VARIAS_VEZES = "LeaseExpiradoVariasVezes";
  static final String PAYLOAD_ILEGIVEL = "PayloadIlegivel";

  private final FilaDoOutbox fila;
  private final NotificarPacientesAfetados lote;
  private final AlertasDoOutbox alertas;
  private final Clock relogio;
  private final int tamanhoDoLote;
  private final Duration lease;
  private final Duration atrasoDeReentrega;
  private final int maximoDeTentativas;

  public DespachanteDoOutbox(
      FilaDoOutbox fila,
      NotificarPacientesAfetados lote,
      AlertasDoOutbox alertas,
      Clock relogio,
      int tamanhoDoLote,
      Duration lease,
      Duration atrasoDeReentrega,
      int maximoDeTentativas) {
    this.fila = Objects.requireNonNull(fila, "fila");
    this.lote = Objects.requireNonNull(lote, "lote");
    this.alertas = Objects.requireNonNull(alertas, "alertas");
    this.relogio = Objects.requireNonNull(relogio, "relogio");
    this.tamanhoDoLote = exigirPositivo(tamanhoDoLote, "tamanhoDoLote");
    this.lease = exigirPositiva(lease, "lease");
    this.atrasoDeReentrega = exigirPositiva(atrasoDeReentrega, "atrasoDeReentrega");
    this.maximoDeTentativas = exigirPositivo(maximoDeTentativas, "maximoDeTentativas");
  }

  /**
   * Uma rodada: até {@code tamanhoDoLote} eventos, um reivindicado por vez. Um evento que quebra
   * não derruba os demais: o primeiro erro é relançado no fim, com os outros como suprimidos.
   */
  public ResumoDoDespacho despachar() {
    int reivindicados = 0;
    int processados = 0;
    int devolvidos = 0;
    int esgotados = 0;
    RuntimeException falha = null;
    for (int i = 0; i < tamanhoDoLote; i++) {
      Reivindicacao reivindicacao;
      try {
        reivindicacao = fila.reivindicar(relogio.instant(), lease, 1);
      } catch (RuntimeException e) {
        if (falha != null) {
          e.addSuppressed(falha);
        }
        throw e;
      }
      if (reivindicacao.estaVazia()) {
        break;
      }
      for (EventoIlegivel ilegivel : reivindicacao.ilegiveis()) {
        reivindicados++;
        esgotados++;
        alertas.eventoEsgotado(ilegivel.eventoId(), ilegivel.tentativas(), PAYLOAD_ILEGIVEL);
      }
      for (EventoReivindicado reivindicado : reivindicacao.eventos()) {
        reivindicados++;
        try {
          switch (despachar(reivindicado)) {
            case PROCESSADO -> processados++;
            case DEVOLVIDO -> devolvidos++;
            case ESGOTADO -> esgotados++;
            case LEASE_PERDIDO -> {}
          }
        } catch (RuntimeException e) {
          if (falha == null) {
            falha = e;
          } else {
            falha.addSuppressed(e);
          }
        }
      }
    }
    if (falha != null) {
      throw falha;
    }
    return new ResumoDoDespacho(reivindicados, processados, devolvidos, esgotados);
  }

  private enum Desfecho {
    PROCESSADO,
    DEVOLVIDO,
    ESGOTADO,
    /** Outro despachante reivindicou o evento depois: esta escrita não vale e não se conta. */
    LEASE_PERDIDO
  }

  private Desfecho despachar(EventoReivindicado reivindicado) {
    if (reivindicado.tentativas() > maximoDeTentativas) {
      return esgotar(reivindicado, LEASE_EXPIRADO_VARIAS_VEZES);
    }
    ResumoDoLote resumo;
    try {
      resumo = lote.executar(reivindicado.evento());
    } catch (RuntimeException e) {
      return falhar(reivindicado, e.getClass().getSimpleName());
    }
    if (resumo.falhasInesperadas() > 0) {
      return falhar(reivindicado, FALHAS_INESPERADAS);
    }
    return fila.marcarProcessado(reivindicado, relogio.instant())
        ? Desfecho.PROCESSADO
        : Desfecho.LEASE_PERDIDO;
  }

  private Desfecho falhar(EventoReivindicado reivindicado, String tipoDoErro) {
    // Só ids, contagem e o nome da classe do erro: nunca a mensagem nem dado de paciente.
    LOG.warn(
        "Evento do outbox não concluído: eventoId={} tentativas={} tipoDoErro={}",
        reivindicado.evento().eventoId(),
        reivindicado.tentativas(),
        tipoDoErro);
    if (reivindicado.tentativas() >= maximoDeTentativas) {
      return esgotar(reivindicado, tipoDoErro);
    }
    Instant reentregarEm = relogio.instant().plus(atrasoDeReentrega);
    return fila.devolver(reivindicado, reentregarEm, tipoDoErro)
        ? Desfecho.DEVOLVIDO
        : Desfecho.LEASE_PERDIDO;
  }

  private Desfecho esgotar(EventoReivindicado reivindicado, String tipoDoErro) {
    if (!fila.esgotar(reivindicado, tipoDoErro)) {
      return Desfecho.LEASE_PERDIDO;
    }
    alertas.eventoEsgotado(reivindicado.evento().eventoId(), reivindicado.tentativas(), tipoDoErro);
    return Desfecho.ESGOTADO;
  }

  private static int exigirPositivo(int valor, String campo) {
    if (valor <= 0) {
      throw new IllegalArgumentException(campo + " deve ser positivo");
    }
    return valor;
  }

  private static Duration exigirPositiva(Duration valor, String campo) {
    Objects.requireNonNull(valor, campo);
    if (valor.isZero() || valor.isNegative()) {
      throw new IllegalArgumentException(campo + " deve ser positivo");
    }
    return valor;
  }
}
