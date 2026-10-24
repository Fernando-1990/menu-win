package br.com.menuwin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling   // ping das conexões SSE da cozinha
public class MenuWinApplication {
    public static void main(String[] args) {
        SpringApplication.run(MenuWinApplication.class, args);
    }
}
