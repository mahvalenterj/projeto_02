package br.gov.saude.upa.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.gov.saude.upa.PostgresIntegrationTest;
import br.gov.saude.upa.application.ConflitoDeConcorrenciaException;
import br.gov.saude.upa.application.MarcarEquipamentoDisponivel;
import br.gov.saude.upa.application.MarcarEquipamentoIndisponivel;
import br.gov.saude.upa.application.UPAPersistencia;
import br.gov.saude.upa.domain.EnderecoAlternativo;
import br.gov.saude.upa.domain.Equipamento;
import br.gov.saude.upa.domain.StatusEquipamento;
import br.gov.saude.upa.domain.TipoEquipamento;
import br.gov.saude.upa.domain.TransicaoDeStatusInvalidaException;
import br.gov.saude.upa.domain.UPA;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Casos de uso de status com banco, transação e outbox reais. CA17 (evento) e parte de CA13. */
@Import(EscritaTestConfiguration.class)
class MarcarEquipamentoIntegracaoTest extends PostgresIntegrationTest {

  @Autowired private MarcarEquipamentoIndisponivel marcarIndisponivel;
  @Autowired private MarcarEquipamentoDisponivel marcarDisponivel;
  @Autowired private EscritaTestConfiguration.OutboxQueFalha outboxQueFalha;
  @Autowired private UPAPersistencia upas;
  @Autowired private JdbcTemplate jdbc;

  private UUID idUpa;
  private UUID tomografo;

  @BeforeEach
  void prepararUpa() {
    outboxQueFalha.falharNaProximaGravacao(false);
    idUpa = UUID.randomUUID();
    tomografo = UUID.randomUUID();
    upas.salvar(
        new UPA(
            idUpa,
            "Madureira",
            List.of(
                new Equipamento(
                    tomografo, TipoEquipamento.TOMOGRAFO, StatusEquipamento.DISPONIVEL)),
            Map.of(TipoEquipamento.TOMOGRAFO, new EnderecoAlternativo("UPA Vizinha"))));
  }

  /** Não deixa evento pendente para trás: outras classes reivindicam do mesmo outbox. */
  @AfterEach
  void limparOsEventosDesteTeste() {
    jdbc.update(
        "DELETE FROM outbox_evento WHERE CAST(payload AS text) LIKE ?", "%" + tomografo + "%");
  }

  private String statusNoBanco() {
    return jdbc.queryForObject(
        "SELECT status FROM equipamento WHERE id = ?", String.class, tomografo);
  }

  private long eventosDoEquipamento() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM outbox_evento WHERE CAST(payload AS text) LIKE ?",
        Long.class,
        "%" + tomografo + "%");
  }

  @Test
  void deveMudarOStatusEGravarOEventoNoOutboxNaMesmaTransacao() {
    UUID eventoId = marcarIndisponivel.executar(idUpa, tomografo);

    assertThat(statusNoBanco()).isEqualTo("INDISPONIVEL");
    assertThat(
            jdbc.queryForObject(
                "SELECT situacao FROM outbox_evento WHERE id = ?", String.class, eventoId))
        .isEqualTo("PENDENTE");
    assertThat(eventosDoEquipamento()).isEqualTo(1);
  }

  @Test
  void deveDesfazerAMudancaDeStatusQuandoFalhaAoGravarNoOutbox() {
    outboxQueFalha.falharNaProximaGravacao(true);

    assertThatThrownBy(() -> marcarIndisponivel.executar(idUpa, tomografo))
        .isInstanceOf(IllegalStateException.class);

    assertThat(statusNoBanco()).isEqualTo("DISPONIVEL");
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM equipamento WHERE id = ?", Long.class, tomografo))
        .isZero();
    assertThat(eventosDoEquipamento()).isZero();
  }

  @Test
  void deveGerarNovoEventoAcadaVezQueOEquipamentoCairDepoisDeVoltar() {
    UUID primeiro = marcarIndisponivel.executar(idUpa, tomografo);
    marcarDisponivel.executar(idUpa, tomografo);
    UUID segundo = marcarIndisponivel.executar(idUpa, tomografo);

    assertThat(segundo).isNotEqualTo(primeiro);
    assertThat(eventosDoEquipamento()).isEqualTo(2);
    assertThat(statusNoBanco()).isEqualTo("INDISPONIVEL");
  }

  @Test
  void deveVoltarADisponivelSemGravarEvento() {
    marcarIndisponivel.executar(idUpa, tomografo);

    marcarDisponivel.executar(idUpa, tomografo);

    assertThat(statusNoBanco()).isEqualTo("DISPONIVEL");
    assertThat(eventosDoEquipamento()).isEqualTo(1);
  }

  @Test
  void deveRejeitarSegundaMarcacaoSemGravarOutroEvento() {
    marcarIndisponivel.executar(idUpa, tomografo);

    assertThatThrownBy(() -> marcarIndisponivel.executar(idUpa, tomografo))
        .isInstanceOf(TransicaoDeStatusInvalidaException.class);

    assertThat(eventosDoEquipamento()).isEqualTo(1);
  }

  @Test
  void deveGerarUmSoEventoQuandoVariosOperadoresMarcamAoMesmoTempo() throws Exception {
    List<Boolean> resultados =
        Disputa.largarJuntas(
            6,
            i -> {
              try {
                marcarIndisponivel.executar(idUpa, tomografo);
                return true;
              } catch (ConflitoDeConcorrenciaException | TransicaoDeStatusInvalidaException e) {
                return false;
              }
            });

    assertThat(Disputa.vencedores(resultados)).isEqualTo(1);
    assertThat(eventosDoEquipamento()).isEqualTo(1);
    assertThat(statusNoBanco()).isEqualTo("INDISPONIVEL");
  }
}
