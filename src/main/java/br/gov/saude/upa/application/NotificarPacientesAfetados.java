package br.gov.saude.upa.application;

import br.gov.saude.upa.domain.AvisoPaciente;
import br.gov.saude.upa.domain.CandidatoAAviso;
import br.gov.saude.upa.domain.ContatoDoPaciente;
import br.gov.saude.upa.domain.DecisaoParaAgendamento;
import br.gov.saude.upa.domain.EquipamentoIndisponivelEvent;
import br.gov.saude.upa.domain.ItemDeRevisaoManual;
import br.gov.saude.upa.domain.MensagemDeAviso;
import br.gov.saude.upa.domain.MotivoCancelamento;
import br.gov.saude.upa.domain.MotivoRevisaoManual;
import br.gov.saude.upa.domain.PoliticaDeReserva;
import br.gov.saude.upa.domain.ResultadoNotificacao;
import br.gov.saude.upa.domain.SelecaoDeAvisos;
import br.gov.saude.upa.domain.StatusEquipamento;
import br.gov.saude.upa.domain.UPA;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Caso de uso do lote: avisa os pacientes afetados por um equipamento indisponível (spec 001).
 *
 * <p>Sem transação envolvendo o lote, de propósito: cada operação de repositório roda na sua
 * transação e a reserva precisa estar gravada antes de chamar o canal (ADR 0001). Os agendamentos
 * são despachados na ordem da seleção, no máximo {@code concorrenciaMaxima} por vez.
 *
 * <p>Exceção consciente à regra "toda falha vira RevisaoManual": uma falha inesperada em um
 * paciente só gera log, alerta e contagem; a reserva fica {@code PENDENTE} e é reprocessada após o
 * tempo-limite, quando o evento for reentregue. Log e alerta levam só ids, contagens e o nome da
 * classe do erro.
 */
public class NotificarPacientesAfetados {

  private static final Logger LOG = LoggerFactory.getLogger(NotificarPacientesAfetados.class);

  private enum Desfecho {
    CONFIRMADO,
    CANCELADO,
    REVISAO_MANUAL,
    INDETERMINADO,
    PULADO,
    FALHA_INESPERADA
  }

  private final UPARepository upas;
  private final AgendamentoRepository agendamentos;
  private final ContatoDePacienteRepository contatos;
  private final ConsultaDeEquipamento consultaDeEquipamento;
  private final NotificadorSms sms;
  private final NotificadorEmail email;
  private final AvisoPacienteRepository avisos;
  private final RevisaoManualRepository revisoes;
  private final AlertasOperacionais alertas;
  private final SelecaoDeAvisos selecao;
  private final PoliticaDeReserva politica;
  private final Clock clock;
  private final Duration tempoLimite;
  private final int concorrenciaMaxima;

  public NotificarPacientesAfetados(
      UPARepository upas,
      AgendamentoRepository agendamentos,
      ContatoDePacienteRepository contatos,
      ConsultaDeEquipamento consultaDeEquipamento,
      NotificadorSms sms,
      NotificadorEmail email,
      AvisoPacienteRepository avisos,
      RevisaoManualRepository revisoes,
      AlertasOperacionais alertas,
      SelecaoDeAvisos selecao,
      PoliticaDeReserva politica,
      Clock clock,
      Duration tempoLimite,
      int concorrenciaMaxima) {
    this.upas = Objects.requireNonNull(upas, "upas");
    this.agendamentos = Objects.requireNonNull(agendamentos, "agendamentos");
    this.contatos = Objects.requireNonNull(contatos, "contatos");
    this.consultaDeEquipamento =
        Objects.requireNonNull(consultaDeEquipamento, "consultaDeEquipamento");
    this.sms = Objects.requireNonNull(sms, "sms");
    this.email = Objects.requireNonNull(email, "email");
    this.avisos = Objects.requireNonNull(avisos, "avisos");
    this.revisoes = Objects.requireNonNull(revisoes, "revisoes");
    this.alertas = Objects.requireNonNull(alertas, "alertas");
    this.selecao = Objects.requireNonNull(selecao, "selecao");
    this.politica = Objects.requireNonNull(politica, "politica");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.tempoLimite = Objects.requireNonNull(tempoLimite, "tempoLimite");
    if (tempoLimite.isZero() || tempoLimite.isNegative()) {
      throw new IllegalArgumentException("tempoLimite deve ser positivo");
    }
    if (concorrenciaMaxima < 1) {
      throw new IllegalArgumentException("concorrenciaMaxima deve ser pelo menos 1");
    }
    this.concorrenciaMaxima = concorrenciaMaxima;
  }

  public ResumoDoLote executar(EquipamentoIndisponivelEvent evento) {
    Objects.requireNonNull(evento, "evento");
    UPA upa =
        upas.buscar(evento.idUpa())
            .orElseThrow(() -> new UpaNaoEncontradaException(evento.idUpa()));

    LocalDate dia = selecao.diaDoLote(evento.momentoFalha());
    AgendamentosDoDia doDia = agendamentos.buscarDoEquipamentoNoDia(evento.idEquipamento(), dia);
    List<CandidatoAAviso> fila =
        selecao.selecionar(doDia.candidatos(), evento.idEquipamento(), evento.momentoFalha());
    List<AgendamentoDescartado> descartados =
        doDia.descartados().stream()
            .filter(d -> selecao.exameAindaNaoComecou(d.inicioExame()))
            .toList();
    if (fila.isEmpty() && descartados.isEmpty()) {
      LOG.info("Lote do evento {} sem agendamentos a avisar", evento.eventoId());
      return new ResumoDoLote(0, 0, 0, 0, 0, 0, 0);
    }

    Lote lote =
        new Lote(
            evento,
            upa,
            avisos.buscarPorEvento(evento.eventoId()),
            revisoes.agendamentosEmRevisao(evento.eventoId()),
            fila.isEmpty()
                ? Map.of()
                : contatos.buscarPorIds(
                    fila.stream()
                        .map(c -> c.agendamento().idPaciente())
                        .collect(Collectors.toUnmodifiableSet())));

    // Os pacientes da fila vêm primeiro; os descartados só gravam a revisão manual.
    List<Desfecho> desfechos = new ArrayList<>(despachar(lote, fila));
    for (AgendamentoDescartado descartado : descartados) {
      desfechos.add(processarDescartado(lote, descartado));
    }

    ResumoDoLote resumo = resumir(fila.size() + descartados.size(), desfechos);
    LOG.info(
        "Lote do evento {} concluído: selecionados={} confirmados={} cancelados={}"
            + " emRevisaoManual={} indeterminados={} pulados={} falhasInesperadas={}",
        evento.eventoId(),
        resumo.selecionados(),
        resumo.confirmados(),
        resumo.cancelados(),
        resumo.emRevisaoManual(),
        resumo.indeterminados(),
        resumo.pulados(),
        resumo.falhasInesperadas());
    return resumo;
  }

  /**
   * A thread do chamador adquire a vaga antes de despachar, na ordem da fila: preserva a prioridade
   * e limita os envios simultâneos.
   */
  private List<Desfecho> despachar(Lote lote, List<CandidatoAAviso> fila) {
    Semaphore vagas = new Semaphore(concorrenciaMaxima);
    List<Future<Desfecho>> futuros = new ArrayList<>();
    List<Desfecho> desfechos = new ArrayList<>();
    boolean interrompido = false;
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (CandidatoAAviso candidato : fila) {
        try {
          vagas.acquire();
        } catch (InterruptedException e) {
          interrompido = true;
          break;
        }
        futuros.add(
            executor.submit(
                () -> {
                  try {
                    return processar(lote, candidato);
                  } finally {
                    vagas.release();
                  }
                }));
      }
      for (Future<Desfecho> futuro : futuros) {
        try {
          desfechos.add(futuro.get());
        } catch (InterruptedException e) {
          interrompido = true;
          break;
        } catch (ExecutionException e) {
          // processar() só deixa escapar Error. A mensagem e a causa do Error podem conter dado
          // de paciente: só o nome da classe sai daqui.
          Throwable causa = e.getCause();
          if (causa instanceof VirtualMachineError fatal) {
            throw fatal;
          }
          String nomeDoErro = causa.getClass().getName();
          LOG.error(
              "Falha fatal no lote: eventoId={} tipo={}", lote.evento().eventoId(), nomeDoErro);
          throw new IllegalStateException(
              "Falha fatal no lote do evento " + lote.evento().eventoId() + ": " + nomeDoErro);
        }
      }
    }
    if (interrompido) {
      Thread.currentThread().interrupt();
      throw new LoteInterrompidoException(lote.evento().eventoId());
    }
    return desfechos;
  }

  private Desfecho processar(Lote lote, CandidatoAAviso candidato) {
    UUID eventoId = lote.evento().eventoId();
    UUID agendamentoId = candidato.agendamento().id();
    try {
      return processarAgendamento(lote, candidato);
    } catch (RuntimeException e) {
      String tipoDoErro = e.getClass().getName();
      LOG.error(
          "Falha inesperada no aviso: eventoId={} agendamentoId={} tipo={}",
          eventoId,
          agendamentoId,
          tipoDoErro);
      alertarFalha(eventoId, agendamentoId, tipoDoErro);
      return Desfecho.FALHA_INESPERADA;
    }
  }

  /**
   * Agendamento sem dado confiável (paciente inexistente, tipo desconhecido): nada a enviar, vai
   * para a revisão manual com alerta, uma vez por evento.
   */
  private Desfecho processarDescartado(Lote lote, AgendamentoDescartado descartado) {
    UUID eventoId = lote.evento().eventoId();
    UUID agendamentoId = descartado.idAgendamento();
    try {
      if (lote.emRevisao().contains(agendamentoId)) {
        return Desfecho.PULADO;
      }
      boolean registrou =
          revisoes.registrar(
              new ItemDeRevisaoManual(
                  eventoId,
                  agendamentoId,
                  MotivoRevisaoManual.DADO_INCONSISTENTE,
                  clock.instant()));
      if (!registrou) {
        return Desfecho.PULADO;
      }
      LOG.warn("Dado inconsistente: eventoId={} agendamentoId={}", eventoId, agendamentoId);
      alertarDadoInconsistente(eventoId, agendamentoId);
      return Desfecho.REVISAO_MANUAL;
    } catch (RuntimeException e) {
      String tipoDoErro = e.getClass().getName();
      LOG.error(
          "Falha inesperada no descartado: eventoId={} agendamentoId={} tipo={}",
          eventoId,
          agendamentoId,
          tipoDoErro);
      alertarFalha(eventoId, agendamentoId, tipoDoErro);
      return Desfecho.FALHA_INESPERADA;
    }
  }

  private Desfecho processarAgendamento(Lote lote, CandidatoAAviso candidato) {
    UUID eventoId = lote.evento().eventoId();
    UUID agendamentoId = candidato.agendamento().id();

    Optional<AvisoPaciente> reservado = adquirirReserva(lote, agendamentoId);
    if (reservado.isEmpty()) {
      return Desfecho.PULADO;
    }
    AvisoPaciente aviso = reservado.get();

    ResultadoNotificacao resultado = decidirResultado(lote, candidato);
    return switch (resultado) {
      case ResultadoNotificacao.Enviada enviada -> gravarEnvioConfirmado(aviso, resultado);
      case ResultadoNotificacao.Cancelada cancelada ->
          gravarDesfecho(aviso, resultado, Desfecho.CANCELADO);
      case ResultadoNotificacao.RevisaoManual revisao -> {
        revisoes.registrar(
            new ItemDeRevisaoManual(eventoId, agendamentoId, revisao.motivo(), clock.instant()));
        yield Desfecho.REVISAO_MANUAL;
      }
      case ResultadoNotificacao.Indeterminada indeterminada -> {
        LOG.warn("Aviso indeterminado: eventoId={} agendamentoId={}", eventoId, agendamentoId);
        alertarIndeterminado(eventoId, agendamentoId);
        yield Desfecho.INDETERMINADO;
      }
    };
  }

  /**
   * Vazio se este agendamento não deve ser enviado agora (já resolvido ou outra execução ganhou).
   */
  private Optional<AvisoPaciente> adquirirReserva(Lote lote, UUID agendamentoId) {
    UUID eventoId = lote.evento().eventoId();
    Optional<AvisoPaciente> existente = Optional.ofNullable(lote.avisos().get(agendamentoId));
    Instant agora = clock.instant();
    DecisaoParaAgendamento decisao =
        politica.decidir(existente, lote.emRevisao().contains(agendamentoId), agora, tempoLimite);
    return switch (decisao) {
      case DecisaoParaAgendamento.Pular pular -> Optional.empty();
      case DecisaoParaAgendamento.Reservar reservar -> {
        AvisoPaciente novo = AvisoPaciente.reservar(eventoId, agendamentoId, agora);
        yield avisos.reservar(novo) ? Optional.of(novo) : Optional.empty();
      }
      case DecisaoParaAgendamento.Reassumir reassumir -> {
        AvisoPaciente abandonado = existente.orElseThrow();
        Instant reservadoEmEsperado = abandonado.reservadoEm();
        if (!avisos.reassumir(eventoId, agendamentoId, reservadoEmEsperado, agora)) {
          yield Optional.empty();
        }
        abandonado.reassumir(agora, tempoLimite);
        yield Optional.of(abandonado);
      }
    };
  }

  /** Relê o status do equipamento e, se ainda indisponível, percorre a cadeia de canais. */
  private ResultadoNotificacao decidirResultado(Lote lote, CandidatoAAviso candidato) {
    StatusEquipamento status =
        consultaDeEquipamento.statusAtual(candidato.agendamento().idEquipamento());
    boolean restabelecido =
        switch (status) {
          case DISPONIVEL -> true;
          case INDISPONIVEL, EM_MANUTENCAO -> false;
        };
    if (restabelecido) {
      return new ResultadoNotificacao.Cancelada(MotivoCancelamento.EQUIPAMENTO_RESTABELECIDO);
    }

    ContatoDoPaciente contato =
        lote.contatos()
            .getOrDefault(candidato.agendamento().idPaciente(), new ContatoDoPaciente(null, null));
    MensagemDeAviso mensagem = MensagemDeAviso.compor(lote.upa(), candidato.agendamento());

    if (contato.possuiTelefone()) {
      Optional<ResultadoNotificacao> porSms =
          desfechoDoCanal(sms.enviar(contato.telefone(), mensagem));
      if (porSms.isPresent()) {
        return porSms.get();
      }
    }
    if (contato.possuiEmail()) {
      Optional<ResultadoNotificacao> porEmail =
          desfechoDoCanal(email.enviar(contato.email(), mensagem));
      if (porEmail.isPresent()) {
        return porEmail.get();
      }
    }
    MotivoRevisaoManual motivo =
        contato.possuiTelefone() || contato.possuiEmail()
            ? MotivoRevisaoManual.CANAIS_ESGOTADOS
            : MotivoRevisaoManual.SEM_CONTATO;
    return new ResultadoNotificacao.RevisaoManual(motivo);
  }

  /**
   * Vazio quando o canal esgotou e vale tentar o próximo; {@code Indeterminado} encerra a cadeia.
   */
  private static Optional<ResultadoNotificacao> desfechoDoCanal(ResultadoDoEnvio envio) {
    return switch (envio) {
      case ResultadoDoEnvio.Confirmado confirmado ->
          Optional.of(new ResultadoNotificacao.Enviada());
      case ResultadoDoEnvio.Indeterminado indeterminado ->
          Optional.of(new ResultadoNotificacao.Indeterminada());
      case ResultadoDoEnvio.Esgotado esgotado -> Optional.empty();
    };
  }

  /**
   * O canal já confirmou: se gravar o desfecho falhar, a reserva fica {@code PENDENTE} e há risco
   * de reenvio, então a operação precisa saber disso por um alerta próprio.
   */
  private Desfecho gravarEnvioConfirmado(AvisoPaciente aviso, ResultadoNotificacao resultado) {
    try {
      return gravarDesfecho(aviso, resultado, Desfecho.CONFIRMADO);
    } catch (RuntimeException e) {
      String tipoDoErro = e.getClass().getName();
      LOG.error(
          "Envio confirmado sem desfecho gravado: eventoId={} agendamentoId={} tipo={}",
          aviso.eventoId(),
          aviso.agendamentoId(),
          tipoDoErro);
      alertarFalha(aviso.eventoId(), aviso.agendamentoId(), "EnvioConfirmadoSemDesfecho");
      return Desfecho.FALHA_INESPERADA;
    }
  }

  private Desfecho gravarDesfecho(
      AvisoPaciente aviso, ResultadoNotificacao resultado, Desfecho desfechoDeSucesso) {
    aviso.aplicar(resultado, clock.instant());
    if (!avisos.registrarDesfecho(aviso)) {
      // A reserva mudou de mãos durante o envio: pode ter havido aviso duplicado.
      LOG.error(
          "Desfecho não gravado: eventoId={} agendamentoId={}",
          aviso.eventoId(),
          aviso.agendamentoId());
      alertarFalha(aviso.eventoId(), aviso.agendamentoId(), "DesfechoNaoGravado");
      return Desfecho.FALHA_INESPERADA;
    }
    return desfechoDeSucesso;
  }

  /**
   * O alerta nunca muda o desfecho: se ele falhar, só ids e o nome da classe do erro são logados.
   */
  private void alertarIndeterminado(UUID eventoId, UUID agendamentoId) {
    try {
      alertas.avisoIndeterminado(eventoId, agendamentoId);
    } catch (RuntimeException e) {
      LOG.error(
          "Alerta de indeterminado não enviado: eventoId={} agendamentoId={} tipo={}",
          eventoId,
          agendamentoId,
          e.getClass().getName());
    }
  }

  private void alertarDadoInconsistente(UUID eventoId, UUID agendamentoId) {
    try {
      alertas.dadoInconsistente(eventoId, agendamentoId);
    } catch (RuntimeException e) {
      LOG.error(
          "Alerta de dado inconsistente não enviado: eventoId={} agendamentoId={} tipo={}",
          eventoId,
          agendamentoId,
          e.getClass().getName());
    }
  }

  /** O alerta nunca derruba o lote: se ele próprio falhar, só o nome da classe do erro é logado. */
  private void alertarFalha(UUID eventoId, UUID agendamentoId, String tipoDoErro) {
    try {
      alertas.falhaInesperada(eventoId, agendamentoId, tipoDoErro);
    } catch (RuntimeException e) {
      LOG.error(
          "Alerta de falha não enviado: eventoId={} agendamentoId={} tipo={}",
          eventoId,
          agendamentoId,
          e.getClass().getName());
    }
  }

  private static ResumoDoLote resumir(int selecionados, List<Desfecho> desfechos) {
    Map<Desfecho, Integer> contagem = new EnumMap<>(Desfecho.class);
    for (Desfecho desfecho : desfechos) {
      contagem.merge(desfecho, 1, Integer::sum);
    }
    return new ResumoDoLote(
        selecionados,
        contagem.getOrDefault(Desfecho.CONFIRMADO, 0),
        contagem.getOrDefault(Desfecho.CANCELADO, 0),
        contagem.getOrDefault(Desfecho.REVISAO_MANUAL, 0),
        contagem.getOrDefault(Desfecho.INDETERMINADO, 0),
        contagem.getOrDefault(Desfecho.PULADO, 0),
        contagem.getOrDefault(Desfecho.FALHA_INESPERADA, 0));
  }

  /** Dados carregados uma vez por lote, só leitura. */
  private record Lote(
      EquipamentoIndisponivelEvent evento,
      UPA upa,
      Map<UUID, AvisoPaciente> avisos,
      Set<UUID> emRevisao,
      Map<UUID, ContatoDoPaciente> contatos) {}
}
