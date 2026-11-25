package br.com.menuwin.pedido;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * menuwin.pedidos.limite: quantos pedidos públicos cabem numa janela de tempo, por loja.
 *  - ip: mesma conexão. Folgado de propósito: o Wi-Fi da loja põe muitos clientes no mesmo IP.
 *  - telefone: mesmo cliente. Ninguém faz mais que uns poucos pedidos de verdade em minutos.
 *  - loja: teto da loja inteira. É a única barreira que não dá para contornar trocando IP ou telefone:
 *    segura uma enxurrada de pedidos falsos na tela da cozinha.
 */
@ConfigurationProperties("menuwin.pedidos.limite")
public record LimitesPedidoProperties(Janela ip, Janela telefone, Janela loja) {

    public record Janela(int maximo, Duration periodo) {}
}
