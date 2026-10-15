package br.gov.saude.upa.infrastructure.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntPredicate;

/** Largada simultânea de várias execuções, para provar garantias do banco sob concorrência. */
final class Disputa {

  private Disputa() {}

  /**
   * Roda {@code execucoes} tarefas que esperam uma largada comum e devolve o resultado de cada uma
   * (o índice da execução vai para a tarefa).
   */
  static List<Boolean> largarJuntas(int execucoes, IntPredicate tarefa) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(execucoes);
    try {
      CountDownLatch prontas = new CountDownLatch(execucoes);
      CountDownLatch largada = new CountDownLatch(1);
      List<Future<Boolean>> futuros = new ArrayList<>();
      for (int i = 0; i < execucoes; i++) {
        int indice = i;
        futuros.add(
            pool.submit(
                () -> {
                  prontas.countDown();
                  largada.await();
                  return tarefa.test(indice);
                }));
      }
      if (!prontas.await(30, TimeUnit.SECONDS)) {
        throw new IllegalStateException("as execuções não ficaram prontas a tempo");
      }
      largada.countDown();
      List<Boolean> resultados = new ArrayList<>();
      for (Future<Boolean> futuro : futuros) {
        resultados.add(futuro.get(60, TimeUnit.SECONDS));
      }
      return resultados;
    } finally {
      pool.shutdownNow();
    }
  }

  static long vencedores(List<Boolean> resultados) {
    return resultados.stream().filter(Boolean::booleanValue).count();
  }
}
