package com.jqgg.glm53;

import com.jqgg.glm53.config.AppPaths;
import com.jqgg.glm53.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Path;

/**
 * 公路广告牌租赁定位管理系统（初版）启动类。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class JqggApplication {

    public static void main(String[] args) {
        // 先确定应用根目录，再加载 config/application.yml：
        // 这样无论从哪个工作目录启动，上传目录、合同目录和本地配置都指向项目所在目录。
        Path baseDir = AppPaths.baseDir();
        if (System.getProperty("app.home") == null || System.getProperty("app.home").isBlank()) {
            System.setProperty("app.home", baseDir.toString());
        }
        if (System.getProperty("spring.config.additional-location") == null) {
            String configDir = baseDir.resolve("config").toString().replace('\\', '/');
            if (!configDir.endsWith("/")) {
                configDir += "/";
            }
            System.setProperty("spring.config.additional-location", "optional:file:" + configDir);
        }
        SpringApplication.run(JqggApplication.class, args);
    }
}
