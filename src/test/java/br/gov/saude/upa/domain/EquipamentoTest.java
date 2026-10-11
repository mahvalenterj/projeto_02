package br.gov.saude.upa.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class EquipamentoTest {

  private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

  @Test
  void deveGuardarIdTipoEStatus() {
    var equipamento = new Equipamento(ID, TipoEquipamento.RAIO_X, StatusEquipamento.DISPONIVEL);

    assertThat(equipamento.id()).isEqualTo(ID);
    assertThat(equipamento.tipo()).isEqualTo(TipoEquipamento.RAIO_X);
    assertThat(equipamento.status()).isEqualTo(StatusEquipamento.DISPONIVEL);
  }

  @Test
  void deveRejeitarCamposNulos() {
    assertThatThrownBy(
            () -> new Equipamento(null, TipoEquipamento.RAIO_X, StatusEquipamento.DISPONIVEL))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Equipamento(ID, null, StatusEquipamento.DISPONIVEL))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Equipamento(ID, TipoEquipamento.RAIO_X, null))
        .isInstanceOf(NullPointerException.class);
  }
}
