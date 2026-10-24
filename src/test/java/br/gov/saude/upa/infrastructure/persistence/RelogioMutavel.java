package br.gov.saude.upa.infrastructure.persistence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** Relógio de teste que só anda quando o teste manda: nenhum teste precisa dormir. */
final class RelogioMutavel extends Clock {

  private final AtomicReference<Instant> agora;

  RelogioMutavel(Instant inicio) {
    this.agora = new AtomicReference<>(inicio);
  }

  void avancar(Duration tempo) {
    agora.updateAndGet(atual -> atual.plus(tempo));
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }

  @Override
  public Instant instant() {
    return agora.get();
  }
}
