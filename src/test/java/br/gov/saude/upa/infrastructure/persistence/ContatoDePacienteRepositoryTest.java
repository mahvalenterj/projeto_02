package br.gov.saude.upa.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.gov.saude.upa.PostgresIntegrationTest;
import br.gov.saude.upa.application.ContatoDePacienteRepository;
import br.gov.saude.upa.domain.ContatoDoPaciente;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Dados sintéticos; ids novos em cada teste porque o banco é compartilhado. */
class ContatoDePacienteRepositoryTest extends PostgresIntegrationTest {

  private static final String NOME = "Fulana Sintetica de Teste";
  private static final String TELEFONE = "+5521900000001";
  private static final String EMAIL = "sintetico.um@exemplo.invalid";

  @Autowired private ContatoDePacienteRepository repositorio;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManagerFactory fabrica;
  @Autowired private TransactionTemplate transacao;

  private boolean estatisticasEstavamLigadas;

  private Statistics ligarEstatisticas() {
    Statistics estatisticas = fabrica.unwrap(SessionFactory.class).getStatistics();
    estatisticasEstavamLigadas = estatisticas.isStatisticsEnabled();
    estatisticas.setStatisticsEnabled(true);
    estatisticas.clear();
    return estatisticas;
  }

  @AfterEach
  void restaurarEstatisticas() {
    fabrica
        .unwrap(SessionFactory.class)
        .getStatistics()
        .setStatisticsEnabled(estatisticasEstavamLigadas);
  }

  private UUID paciente(String telefone, String email) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO paciente (id, nome, telefone, email, data_nascimento) VALUES (?, ?, ?, ?, ?)",
        id,
        NOME,
        telefone,
        email,
        java.sql.Date.valueOf(LocalDate.of(1975, 2, 3)));
    return id;
  }

  @Test
  void deveDevolverTelefoneEEmailQuandoOsDoisExistem() {
    UUID id = paciente(TELEFONE, EMAIL);

    Map<UUID, ContatoDoPaciente> contatos = repositorio.buscarPorIds(Set.of(id));

    assertThat(contatos).containsOnlyKeys(id);
    assertThat(contatos.get(id).telefone()).isEqualTo(TELEFONE);
    assertThat(contatos.get(id).email()).isEqualTo(EMAIL);
  }

  @Test
  void deveManterNulosOsContatosQueOPacienteNaoTem() {
    UUID soEmail = paciente(null, EMAIL);
    UUID soTelefone = paciente(TELEFONE, null);
    UUID nenhum = paciente(null, null);

    Map<UUID, ContatoDoPaciente> contatos =
        repositorio.buscarPorIds(Set.of(soEmail, soTelefone, nenhum));

    assertThat(contatos.get(soEmail).possuiTelefone()).isFalse();
    assertThat(contatos.get(soEmail).email()).isEqualTo(EMAIL);
    assertThat(contatos.get(soTelefone).telefone()).isEqualTo(TELEFONE);
    assertThat(contatos.get(soTelefone).possuiEmail()).isFalse();
    assertThat(contatos.get(nenhum).possuiTelefone()).isFalse();
    assertThat(contatos.get(nenhum).possuiEmail()).isFalse();
  }

  @Test
  void deveDeixarForaDoMapaOIdSemRegistro() {
    UUID existente = paciente(TELEFONE, EMAIL);
    UUID ausente = UUID.randomUUID();

    Map<UUID, ContatoDoPaciente> contatos = repositorio.buscarPorIds(Set.of(existente, ausente));

    assertThat(contatos).containsOnlyKeys(existente);
  }

  @Test
  void deveDevolverMapaVazioParaConjuntoVazioSemIrAoBanco() {
    Statistics estatisticas = ligarEstatisticas();

    assertThat(repositorio.buscarPorIds(Set.of())).isEmpty();
    assertThat(estatisticas.getPrepareStatementCount()).isZero();
  }

  @Test
  void deveBuscarVariosPacientesComUmaUnicaConsulta() {
    Set<UUID> ids =
        Set.of(
            paciente(TELEFONE, EMAIL),
            paciente(null, EMAIL),
            paciente(TELEFONE, null),
            paciente(null, null),
            paciente(TELEFONE, EMAIL));
    Statistics estatisticas = ligarEstatisticas();

    Map<UUID, ContatoDoPaciente> contatos = repositorio.buscarPorIds(ids);

    assertThat(contatos).hasSize(5);
    assertThat(estatisticas.getPrepareStatementCount()).isEqualTo(1);
  }

  @Test
  void deveRejeitarNoBancoTelefoneEEmailEmBrancoInclusiveComTabENovaLinha() {
    for (String branco : new String[] {"", " ", "\t", "\n", " \t\n "}) {
      assertThatThrownBy(() -> paciente(branco, null))
          .as("telefone [%s]", branco.replace("\n", "\\n").replace("\t", "\\t"))
          .isInstanceOf(DataIntegrityViolationException.class);
      assertThatThrownBy(() -> paciente(null, branco))
          .as("email [%s]", branco.replace("\n", "\\n").replace("\t", "\\t"))
          .isInstanceOf(DataIntegrityViolationException.class);
    }
  }

  @Test
  void deveAnularOContatoInteiroQuandoUmCampoEstaEmBrancoEOCheckForContornado() {
    UUID comLinhaRuim = UUID.randomUUID();
    UUID emailEmBranco = UUID.randomUUID();
    UUID normal = paciente(TELEFONE, EMAIL);

    // Contorna os CHECKs só dentro de uma transação desfeita no fim (DDL do Postgres é
    // transacional): defesa em profundidade do adapter contra dado ruim que já esteja no banco.
    // Se a transação falhar antes do rollback, o finally recria cada CHECK por conta própria.
    Map<UUID, ContatoDoPaciente> contatos;
    try {
      contatos =
          transacao.execute(
              status -> {
                status.setRollbackOnly();
                jdbc.execute("ALTER TABLE paciente DROP CONSTRAINT ck_paciente_telefone");
                jdbc.execute("ALTER TABLE paciente DROP CONSTRAINT ck_paciente_email");
                jdbc.update(
                    "INSERT INTO paciente (id, nome, telefone, email, data_nascimento)"
                        + " VALUES (?, 'Sintetico', E'\\t', ?, DATE '1975-02-03')",
                    comLinhaRuim,
                    EMAIL);
                jdbc.update(
                    "INSERT INTO paciente (id, nome, telefone, email, data_nascimento)"
                        + " VALUES (?, 'Sintetico', ?, ' ', DATE '1975-02-03')",
                    emailEmBranco,
                    TELEFONE);
                return repositorio.buscarPorIds(Set.of(comLinhaRuim, emailEmBranco, normal));
              });
    } finally {
      try {
        recriarCheckDeBranco("ck_paciente_telefone", "telefone");
      } finally {
        recriarCheckDeBranco("ck_paciente_email", "email");
      }
    }

    assertThat(contatos).containsOnlyKeys(comLinhaRuim, emailEmBranco, normal);
    assertThat(contatos.get(comLinhaRuim).telefone()).isNull();
    assertThat(contatos.get(comLinhaRuim).email()).isNull();
    assertThat(contatos.get(emailEmBranco).telefone()).isNull();
    assertThat(contatos.get(emailEmBranco).email()).isNull();
    assertThat(contatos.get(normal).telefone()).isEqualTo(TELEFONE);
    assertThat(contatos.get(normal).email()).isEqualTo(EMAIL);
  }

  /** Idempotente: existe ou não, o CHECK sai daqui igual ao da migration V2. */
  private void recriarCheckDeBranco(String nome, String coluna) {
    jdbc.execute("ALTER TABLE paciente DROP CONSTRAINT IF EXISTS " + nome);
    jdbc.execute(
        "ALTER TABLE paciente ADD CONSTRAINT "
            + nome
            + " CHECK ("
            + coluna
            + " IS NULL OR "
            + coluna
            + " !~ '^\\s*$')");
  }

  @Test
  void deveParticionarEmBlocosDeMilIdsEUnirOsResultados() {
    List<UUID> existentes = new ArrayList<>();
    List<Object[]> linhas = new ArrayList<>();
    for (int i = 0; i < 1_100; i++) {
      UUID id = UUID.randomUUID();
      existentes.add(id);
      linhas.add(new Object[] {id, NOME, "+55219" + String.format("%08d", i), null});
    }
    jdbc.batchUpdate(
        "INSERT INTO paciente (id, nome, telefone, email, data_nascimento)"
            + " VALUES (?, ?, ?, ?, DATE '1975-02-03')",
        linhas);
    Set<UUID> ids = new HashSet<>(existentes);
    while (ids.size() < 2_500) {
      ids.add(UUID.randomUUID());
    }
    Statistics estatisticas = ligarEstatisticas();

    Map<UUID, ContatoDoPaciente> contatos = repositorio.buscarPorIds(ids);

    assertThat(contatos).hasSize(1_100).containsOnlyKeys(existentes);
    assertThat(estatisticas.getPrepareStatementCount()).isEqualTo(3);
  }

  @Test
  void deveUsarUmaConsultaParaExatamenteMilIds() {
    Set<UUID> ids = new HashSet<>();
    while (ids.size() < 1_000) {
      ids.add(UUID.randomUUID());
    }
    Statistics estatisticas = ligarEstatisticas();

    repositorio.buscarPorIds(ids);

    assertThat(estatisticas.getPrepareStatementCount()).isEqualTo(1);
  }

  @Test
  void naoDeveExporDadoDePacienteNoToStringDaEntidadeNemDaLinha() {
    UUID id = paciente(TELEFONE, EMAIL);

    String daEntidade;
    try (EntityManager em = fabrica.createEntityManager()) {
      daEntidade = em.find(PacienteJpa.class, id).toString();
    }
    String daLinha = new LinhaDeContato(id, TELEFONE, EMAIL).toString();

    for (String texto : new String[] {daEntidade, daLinha}) {
      assertThat(texto)
          .doesNotContain(NOME)
          .doesNotContain(TELEFONE)
          .doesNotContain(EMAIL)
          .doesNotContain("1975");
    }
  }
}
