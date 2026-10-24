package br.gov.saude.upa.infrastructure.persistence;

import br.gov.saude.upa.domain.TipoEquipamento;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.MapKeyEnumerated;
import jakarta.persistence.Table;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Linha de {@code upa} com os endereços alternativos. Sem regra de negócio. */
@Entity
@Table(name = "upa")
class UpaJpa {

  @Id private UUID id;

  @Column(name = "nome", nullable = false)
  private String nome;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "upa_endereco_alternativo", joinColumns = @JoinColumn(name = "upa_id"))
  @MapKeyColumn(name = "tipo")
  @MapKeyEnumerated(EnumType.STRING)
  @Column(name = "texto", nullable = false)
  private Map<TipoEquipamento, String> enderecos = new HashMap<>();

  protected UpaJpa() {}

  UpaJpa(UUID id, String nome, Map<TipoEquipamento, String> enderecos) {
    this.id = id;
    this.nome = nome;
    this.enderecos = new HashMap<>(enderecos);
  }

  UUID id() {
    return id;
  }

  String nome() {
    return nome;
  }

  Map<TipoEquipamento, String> enderecos() {
    return Map.copyOf(enderecos);
  }
}
