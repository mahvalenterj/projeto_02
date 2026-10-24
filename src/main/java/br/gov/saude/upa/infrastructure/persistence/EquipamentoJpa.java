package br.gov.saude.upa.infrastructure.persistence;

import br.gov.saude.upa.domain.StatusEquipamento;
import br.gov.saude.upa.domain.TipoEquipamento;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

/**
 * Linha de {@code equipamento}. O {@code @Version} é o lock otimista: gravar com uma versão velha
 * falha. Sem regra de negócio: a regra de transição é do domínio.
 */
@Entity
@Table(name = "equipamento")
class EquipamentoJpa {

  @Id private UUID id;

  @Column(name = "upa_id", nullable = false)
  private UUID upaId;

  @Enumerated(EnumType.STRING)
  @Column(name = "tipo", nullable = false)
  private TipoEquipamento tipo;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private StatusEquipamento status;

  /** {@code null} em equipamento novo; o Hibernate grava 0 no primeiro insert. */
  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  protected EquipamentoJpa() {}

  EquipamentoJpa(
      UUID id, UUID upaId, TipoEquipamento tipo, StatusEquipamento status, Long version) {
    this.id = id;
    this.upaId = upaId;
    this.tipo = tipo;
    this.status = status;
    this.version = version;
  }

  UUID id() {
    return id;
  }

  UUID upaId() {
    return upaId;
  }

  TipoEquipamento tipo() {
    return tipo;
  }

  StatusEquipamento status() {
    return status;
  }

  Long version() {
    return version;
  }
}
