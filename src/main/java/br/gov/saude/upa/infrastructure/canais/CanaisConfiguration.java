package br.gov.saude.upa.infrastructure.canais;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Liga as propriedades {@code upa.canais.*} aos adapters de SMS e e-mail. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CanaisProperties.class)
class CanaisConfiguration {}
