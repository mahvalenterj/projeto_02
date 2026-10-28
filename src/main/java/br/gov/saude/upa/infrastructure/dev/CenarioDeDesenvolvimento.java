package br.gov.saude.upa.infrastructure.dev;

import br.gov.saude.upa.application.UPAPersistencia;
import br.gov.saude.upa.domain.EnderecoAlternativo;
import br.gov.saude.upa.domain.Equipamento;
import br.gov.saude.upa.domain.Fuso;
import br.gov.saude.upa.domain.StatusEquipamento;
import br.gov.saude.upa.domain.TipoEquipamento;
import br.gov.saude.upa.domain.UPA;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * SOMENTE DESENVOLVIMENTO: cria uma UPA com um tomógrafo {@code DISPONIVEL} e quatro pacientes
 * SINTÉTICOS (telefone {@code +55219999xxxx}, e-mail {@code @exemplo.invalid}) com tomografia hoje
 * em America/Sao_Paulo, depois do agora. Pacientes e agendamentos são gravados direto nas tabelas
 * (o sistema só lê esses dados; quem os grava em produção é o SISREG/CISREG). O log leva só ids.
 */
class CenarioDeDesenvolvimento {

  private static final Logger LOG = LoggerFactory.getLogger(CenarioDeDesenvolvimento.class);
  private static final Duration PASSO_MAXIMO = Duration.ofMinutes(30);

  /** Telefone e e-mail de cada paciente sintético: os dois canais aparecem no WireMock. */
  private static final boolean[][] CONTATOS = {
    {true, true}, {true, false}, {false, true}, {true, true}
  };

  private final UPAPersistencia upas;
  private final JdbcTemplate jdbc;
  private final Clock relogio;

  CenarioDeDesenvolvimento(UPAPersistencia upas, JdbcTemplate jdbc, Clock relogio) {
    this.upas = Objects.requireNonNull(upas, "upas");
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.relogio = Objects.requireNonNull(relogio, "relogio");
  }

  @Transactional
  CenarioCriado criar() {
    Instant agora = relogio.instant();
    UUID upaId = UUID.randomUUID();
    UUID tomografo = UUID.randomUUID();
    String sufixo = upaId.toString().substring(0, 8);
    upas.salvar(
        new UPA(
            upaId,
            "UPA Sintetica Dev " + sufixo,
            List.of(
                new Equipamento(
                    tomografo, TipoEquipamento.TOMOGRAFO, StatusEquipamento.DISPONIVEL)),
            Map.of(
                TipoEquipamento.TOMOGRAFO,
                new EnderecoAlternativo("UPA Vizinha Sintetica, Rua Ficticia 100"))));

    Duration passo = passo(agora);
    List<AgendamentoCriado> agendamentos = new ArrayList<>();
    for (int i = 0; i < CONTATOS.length; i++) {
      boolean comTelefone = CONTATOS[i][0];
      boolean comEmail = CONTATOS[i][1];
      UUID paciente = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO paciente (id, nome, telefone, email, data_nascimento) VALUES (?, ?, ?, ?,"
              + " ?)",
          paciente,
          "Paciente Sintetico Dev " + (i + 1),
          comTelefone ? telefoneSintetico() : null,
          comEmail ? "paciente.dev." + (i + 1) + "." + sufixo + "@exemplo.invalid" : null,
          java.sql.Date.valueOf(LocalDate.of(1950 + 10 * i, 1 + i, 10 + i)));
      UUID agendamento = UUID.randomUUID();
      Instant inicio = agora.plus(passo.multipliedBy(i + 1L));
      jdbc.update(
          "INSERT INTO agendamento_exame (id, tipo, paciente_id, upa_id, equipamento_id,"
              + " criado_em, inicio_exame) VALUES (?, 'TOMOGRAFIA', ?, ?, ?, ?, ?)",
          agendamento,
          paciente,
          upaId,
          tomografo,
          Timestamp.from(agora),
          Timestamp.from(inicio));
      agendamentos.add(
          new AgendamentoCriado(agendamento, paciente, inicio, comTelefone ? "SMS" : "EMAIL"));
    }
    LOG.info(
        "Cenario de desenvolvimento criado: upaId={}, equipamentoId={}, agendamentos={}",
        upaId,
        tomografo,
        agendamentos.size());
    return new CenarioCriado(upaId, tomografo, List.copyOf(agendamentos));
  }

  /** Até 30 min entre exames, encolhendo para todos caberem antes da meia-noite de São Paulo. */
  private Duration passo(Instant agora) {
    Instant meiaNoite =
        agora
            .atZone(Fuso.SAO_PAULO)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(Fuso.SAO_PAULO)
            .toInstant();
    Duration cabe = Duration.between(agora, meiaNoite).dividedBy(CONTATOS.length + 1L);
    return cabe.compareTo(PASSO_MAXIMO) < 0 ? cabe : PASSO_MAXIMO;
  }

  private static String telefoneSintetico() {
    return "+55219999" + String.format("%04d", ThreadLocalRandom.current().nextInt(10_000));
  }

  record CenarioCriado(UUID upaId, UUID equipamentoId, List<AgendamentoCriado> agendamentos) {}

  record AgendamentoCriado(
      UUID agendamentoId, UUID pacienteId, Instant inicioExame, String canal) {}
}
