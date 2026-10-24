package br.gov.saude.upa.infrastructure.persistence;

import br.gov.saude.upa.application.EventoIlegivel;
import br.gov.saude.upa.application.EventoReivindicado;
import br.gov.saude.upa.application.FilaDoOutbox;
import br.gov.saude.upa.application.OutboxRepository;
import br.gov.saude.upa.application.Reivindicacao;
import br.gov.saude.upa.domain.EquipamentoIndisponivelEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox em SQL. {@code gravar} participa da transação do chamador (mesma transação da mudança de
 * status). A reivindicação é um único {@code UPDATE ... RETURNING} sobre linhas travadas com {@code
 * FOR UPDATE SKIP LOCKED}: dois despachantes nunca recebem o mesmo evento. {@code reivindicado_ate}
 * guarda o lease e também o atraso de reentrega. {@code @Component}, não {@code @Repository}.
 */
@Component
class OutboxRepositoryAdapter implements OutboxRepository, FilaDoOutbox {

  private static final String TIPO_DO_EVENTO = "EquipamentoIndisponivelEvent";
  private static final int TAMANHO_MAXIMO_DO_ERRO = 200;
  private static final String PAYLOAD_ILEGIVEL = "PayloadIlegivel";

  private static final String REIVINDICAR =
      """
      WITH candidatos AS MATERIALIZED (
             SELECT id FROM outbox_evento
              WHERE situacao = 'PENDENTE'
                AND (reivindicado_ate IS NULL OR reivindicado_ate <= :agora)
              ORDER BY ocorrido_em, id
              LIMIT :limite
                FOR UPDATE SKIP LOCKED)
      UPDATE outbox_evento o
         SET reivindicado_ate = :ate, tentativas = o.tentativas + 1
        FROM candidatos c
       WHERE o.id = c.id
      RETURNING o.id AS id, CAST(o.payload AS text) AS payload, o.tentativas AS tentativas
      """;

  private static final String MARCAR_PROCESSADO =
      """
      UPDATE outbox_evento
         SET situacao = 'PROCESSADO', processado_em = :agora, reivindicado_ate = NULL
       WHERE id = :id AND situacao = 'PENDENTE' AND tentativas = :tentativas
      """;

  private static final String DEVOLVER =
      """
      UPDATE outbox_evento
         SET reivindicado_ate = :reentregarEm, tipo_ultimo_erro = :erro
       WHERE id = :id AND situacao = 'PENDENTE' AND tentativas = :tentativas
      """;

  private static final String ESGOTAR =
      """
      UPDATE outbox_evento
         SET situacao = 'ESGOTADO', reivindicado_ate = NULL, tipo_ultimo_erro = :erro
       WHERE id = :id AND situacao = 'PENDENTE' AND tentativas = :tentativas
      """;

  /** Forma do evento no JSON do outbox: só ids e o instante. */
  record Payload(UUID eventoId, UUID idEquipamento, UUID idUpa, String momentoFalha) {}

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  OutboxRepositoryAdapter(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  @Transactional
  public void gravar(EquipamentoIndisponivelEvent evento) {
    Objects.requireNonNull(evento, "evento");
    Instant momento = micros(evento.momentoFalha());
    jdbc.sql(
            """
            INSERT INTO outbox_evento (id, tipo, payload, ocorrido_em)
            VALUES (:id, :tipo, CAST(:payload AS jsonb), :ocorridoEm)
            """)
        .param("id", evento.eventoId())
        .param("tipo", TIPO_DO_EVENTO)
        .param("payload", serializar(evento, momento))
        .param("ocorridoEm", timestamp(momento))
        .update();
  }

  @Override
  @Transactional
  public Reivindicacao reivindicar(Instant agora, Duration lease, int limite) {
    Objects.requireNonNull(agora, "agora");
    Objects.requireNonNull(lease, "lease");
    if (limite <= 0) {
      throw new IllegalArgumentException("limite deve ser positivo");
    }
    Instant instanteAgora = micros(agora);
    List<Linha> linhas =
        jdbc.sql(REIVINDICAR)
            .param("ate", timestamp(instanteAgora.plus(lease)))
            .param("agora", timestamp(instanteAgora))
            .param("limite", limite)
            .query(
                (rs, n) ->
                    new Linha(
                        rs.getObject("id", UUID.class),
                        rs.getString("payload"),
                        rs.getInt("tentativas")))
            .list();
    List<EventoReivindicado> eventos = new ArrayList<>();
    List<EventoIlegivel> ilegiveis = new ArrayList<>();
    for (Linha linha : linhas) {
      Optional<EquipamentoIndisponivelEvent> evento = desserializar(linha);
      if (evento.isPresent()) {
        eventos.add(new EventoReivindicado(evento.get(), linha.tentativas()));
      } else {
        // Linha que nunca será legível: esgota na mesma transação e não trava as demais.
        esgotarPorId(linha.id(), linha.tentativas(), PAYLOAD_ILEGIVEL);
        ilegiveis.add(new EventoIlegivel(linha.id(), linha.tentativas()));
      }
    }
    eventos.sort(
        Comparator.comparing((EventoReivindicado r) -> r.evento().momentoFalha())
            .thenComparing(r -> r.evento().eventoId()));
    return new Reivindicacao(eventos, ilegiveis);
  }

  private record Linha(UUID id, String payload, int tentativas) {}

  @Override
  @Transactional
  public boolean marcarProcessado(EventoReivindicado reivindicado, Instant agora) {
    return jdbc.sql(MARCAR_PROCESSADO)
            .param("id", reivindicado.evento().eventoId())
            .param("tentativas", reivindicado.tentativas())
            .param("agora", timestamp(micros(agora)))
            .update()
        == 1;
  }

  @Override
  @Transactional
  public boolean devolver(
      EventoReivindicado reivindicado, Instant reentregarEm, String tipoDoErro) {
    return jdbc.sql(DEVOLVER)
            .param("id", reivindicado.evento().eventoId())
            .param("tentativas", reivindicado.tentativas())
            .param("reentregarEm", timestamp(micros(reentregarEm)))
            .param("erro", limitar(tipoDoErro))
            .update()
        == 1;
  }

  @Override
  @Transactional
  public boolean esgotar(EventoReivindicado reivindicado, String tipoDoErro) {
    return esgotarPorId(reivindicado.evento().eventoId(), reivindicado.tentativas(), tipoDoErro);
  }

  private boolean esgotarPorId(UUID id, int tentativas, String tipoDoErro) {
    return jdbc.sql(ESGOTAR)
            .param("id", id)
            .param("tentativas", tentativas)
            .param("erro", limitar(tipoDoErro))
            .update()
        == 1;
  }

  private String serializar(EquipamentoIndisponivelEvent evento, Instant momento) {
    try {
      return json.writeValueAsString(
          new Payload(
              evento.eventoId(), evento.idEquipamento(), evento.idUpa(), momento.toString()));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Evento " + evento.eventoId() + " não serializável", e);
    }
  }

  /** Vazio quando o payload não é JSON válido do evento, ou não bate com o id da linha. */
  private Optional<EquipamentoIndisponivelEvent> desserializar(Linha linha) {
    try {
      Payload payload = json.readValue(linha.payload(), Payload.class);
      if (payload == null
          || !linha.id().equals(payload.eventoId())
          || payload.idEquipamento() == null
          || payload.idUpa() == null
          || payload.momentoFalha() == null) {
        return Optional.empty();
      }
      return Optional.of(
          new EquipamentoIndisponivelEvent(
              payload.eventoId(),
              payload.idEquipamento(),
              payload.idUpa(),
              Instant.parse(payload.momentoFalha())));
    } catch (JsonProcessingException | DateTimeParseException e) {
      return Optional.empty();
    }
  }

  private static String limitar(String tipoDoErro) {
    Objects.requireNonNull(tipoDoErro, "tipoDoErro");
    return tipoDoErro.length() <= TAMANHO_MAXIMO_DO_ERRO
        ? tipoDoErro
        : tipoDoErro.substring(0, TAMANHO_MAXIMO_DO_ERRO);
  }

  /**
   * O Postgres guarda microssegundos; truncar na escrita evita comparação que falha em silêncio.
   */
  private static Instant micros(Instant instante) {
    return instante.truncatedTo(ChronoUnit.MICROS);
  }

  private static OffsetDateTime timestamp(Instant instante) {
    return instante.atOffset(ZoneOffset.UTC);
  }
}
