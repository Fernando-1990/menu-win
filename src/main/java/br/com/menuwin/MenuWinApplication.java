package br.com.menuwin;

import org.springframework.boot.SpringApplication;
import br.com.menuwin.pedido.LimitesPedidoProperties;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling   // ping das conexões SSE da cozinha e limpeza do limite de pedidos
@EnableConfigurationProperties(LimitesPedidoProperties.class)
public class MenuWinApplication {
    public static void main(String[] args) {
        SpringApplication.run(MenuWinApplication.class, args);
    }
}
