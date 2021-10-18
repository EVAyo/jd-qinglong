package com.meread.selenium.service;

import com.meread.selenium.bean.*;
import com.meread.selenium.util.WebDriverOpCallBack;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.html5.LocalStorage;
import org.openqa.selenium.html5.SessionStorage;
import org.openqa.selenium.html5.WebStorage;
import org.openqa.selenium.remote.Augmenter;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ResourceLoader;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author yangxg
 * @date 2021/9/7
 */
@Component
@Slf4j
@Profile({"default", "local"})
public class WebDriverManagerLocal extends BaseWebDriverManager {
    public static final Pattern PATTERN = Pattern.compile("--port=(\\d+)");
    @Autowired
    private WSManager wsManager;

    @Autowired
    private BotService botService;

    public WebDriverManagerLocal(@Value("${chrome.driver.path}") String chromeDriverPath,
                                 @Autowired RestTemplate restTemplate,
                                 @Autowired ThreadPoolTaskExecutor threadPoolTaskExecutor,
                                 @Autowired ResourceLoader resourceLoader,
                                 @Value("${chrome.headless}") boolean headless,
                                 @Value("${env.path}") String envPath,
                                 @Value("${op.timeout}") int opTimeout,
                                 @Value("${chrome.timeout}") int chromeTimeout,
                                 @Value("${SE_NODE_MAX_SESSIONS}") String maxSessionFromProps) {
        super(chromeDriverPath, restTemplate, threadPoolTaskExecutor, resourceLoader, headless, envPath, opTimeout, chromeTimeout, maxSessionFromProps);
    }

    /**
     * 和grid同步chrome状态，清理失效的session，并移除本地缓存
     */
    @Override
    public void heartbeat() {
        List<String> removeChromeSessionIds = new ArrayList<>();
        Set<Integer> servicePorts = new HashSet<>();
        for (MyChrome myChrome : chromes.values()) {
            if (myChrome.isExpire()) {
                removeChromeSessionIds.add(myChrome.getChromeSessionId());
            }
        }
        for (String s : removeChromeSessionIds) {
            releaseWebDriver(s, true);
        }

        for (MyChrome myChrome : chromes.values()) {
            int port = myChrome.getChromeDriverService().getUrl().getPort();
            servicePorts.add(port);
        }

        Set<Integer> systemChromes = getSystemChromes();

        if (systemChromes.size() > servicePorts.size()) {
            log.info("servicePorts " + servicePorts.size() + " systemPorts " + systemChromes.size());
        }
//        if (systemChromes.size() > servicePorts.size()) {
//            systemChromes.removeAll(servicePorts);
//            for (Integer lajiPort : systemChromes) {
//                log.info("kill " + lajiPort);
//                try {
//                    new RemoteWebDriver(new URL("http://localhost:" + lajiPort), chromeOptions).quit();
//                } catch (MalformedURLException e) {
//                    e.printStackTrace();
//                }
//            }
//        }

        //clean clients
        Iterator<Map.Entry<String, MyChromeClient>> it = clients.entrySet().iterator();
        while (it.hasNext()) {
            MyChromeClient client = it.next().getValue();
            if (client.isExpire()) {
                if (client.getUserTrackId() != null) {
                    wsManager.socketSessionPool.remove(client.getUserTrackId());
                    wsManager.lastPageStatus.remove(client.getUserTrackId());
                }
                it.remove();
                MyChrome myChrome = chromes.get(client.getChromeSessionId());
                if (myChrome != null) {
                    myChrome.setUserTrackId(null);
                }
            }
        }

        int shouldCreate = CAPACITY - chromes.size();
        if (shouldCreate > 0) {
            createOneChrome();
        }
    }

    private void createOneChrome() {
        ChromeDriverService chromeDriverService = new ChromeDriverService.Builder().usingAnyFreePort().build();
        try {
            chromeDriverService.start();
            RemoteWebDriver webDriver = new RemoteWebDriver(chromeDriverService.getUrl(), chromeOptions);
            webDriver.manage().timeouts().implicitlyWait(10, TimeUnit.SECONDS).pageLoadTimeout(20, TimeUnit.SECONDS).setScriptTimeout(20, TimeUnit.SECONDS);
            MyChrome myChrome = new MyChrome(webDriver, chromeDriverService, System.currentTimeMillis() + chromeTimeout * 1000L);
            //计算chrome实例的最大存活时间
            chromes.put(webDriver.getSessionId().toString(), myChrome);
            log.warn("create a chrome " + webDriver.getSessionId().toString() + " 总容量 = " + CAPACITY + ", 当前容量" + chromes.size());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void quit(MyChrome chrome) {
        log.info("quit " + chrome.getChromeSessionId());
        try {
            chrome.getWebDriver().quit();
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            chrome.getChromeDriverService().stop();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Set<Integer> getSystemChromes() {
        Set<Integer> servicePorts = new HashSet<>();
        String[] cmd = new String[]{"sh", "-c", "ps -ef | grep chromedriver"};
        try {
            Process process = Runtime.getRuntime().exec(cmd);
            List<String> output = new ArrayList<>();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                output.add(line);
            }
            int exitVal = process.waitFor();
            if (exitVal == 0) {
                for (String s : output) {
                    Matcher matcher = PATTERN.matcher(s);
                    if (matcher.find()) {
                        int port = Integer.parseInt(matcher.group(1));
                        servicePorts.add(port);
                    }
                }
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
        return servicePorts;
    }

    @Override
    public void createChrome() {
        ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        int create = CAPACITY - chromes.size();
        if (create <= 0) {
            return;
        }
        CountDownLatch cdl = new CountDownLatch(create);
        for (int i = 0; i < create; i++) {
            executorService.execute(() -> {
                try {
                    createOneChrome();
                } finally {
                    cdl.countDown();
                }
            });
        }

        try {
            cdl.await();
        } catch (InterruptedException e) {
            throw new RuntimeException("无法创建浏览器实例");
        }
        executorService.shutdown();

        if (chromes.isEmpty()) {
            throw new RuntimeException("无法创建浏览器实例");
        }
    }

    @Override
    public void releaseWebDriver(String removeChromeSessionId, boolean quit) {
        Iterator<Map.Entry<String, MyChrome>> iterator = chromes.entrySet().iterator();
        while (iterator.hasNext()) {
            MyChrome myChrome = iterator.next().getValue();
            String sessionId = myChrome.getWebDriver().getSessionId().toString();
            if (sessionId.equals(removeChromeSessionId)) {
                try {
                    //获取chrome的失效时间
                    long chromeExpireTime = myChrome.getExpireTime();
                    long clientExpireTime = 0;
                    //获取客户端的失效时间
                    String userTrackId = myChrome.getUserTrackId();
                    if (userTrackId != null) {
                        MyChromeClient client = clients.get(userTrackId);
                        if (client != null) {
                            clientExpireTime = client.getExpireTime();
                        }
                    }
                    long chromeRemain = clientExpireTime <= 0 ? 0 : (chromeExpireTime - clientExpireTime) / 1000;
                    log.info("chrome剩余时间" + chromeRemain + " 配置的操作时限" + opTimeout);
                    //chrome的存活时间不够一个opTime时间，则chrome不退出，只清理客户端引用
                    if (chromeRemain > opTimeout && !quit) {
                        if (wsManager.getLastPageStatus().size() > 0 && userTrackId != null) {
                            wsManager.getLastPageStatus().remove(userTrackId);
                        }
                        if (wsManager.socketSessionPool.size() > 0 && userTrackId != null) {
                            wsManager.socketSessionPool.remove(userTrackId);
                        }
                        WebStorage webStorage = (WebStorage) new Augmenter().augment(myChrome.getWebDriver());
                        if (webStorage != null) {
                            LocalStorage localStorage = webStorage.getLocalStorage();
                            if (localStorage != null) {
                                localStorage.clear();
                            }
                            SessionStorage sessionStorage = webStorage.getSessionStorage();
                            if (sessionStorage != null) {
                                sessionStorage.clear();
                            }
                        }
                        myChrome.getWebDriver().manage().deleteAllCookies();
                        log.info("clean chrome binding: " + sessionId);
                    } else {
                        if (wsManager.getLastPageStatus().size() > 0 && userTrackId != null) {
                            wsManager.getLastPageStatus().remove(userTrackId);
                        }
                        if (wsManager.socketSessionPool.size() > 0 && userTrackId != null) {
                            wsManager.socketSessionPool.remove(userTrackId);
                        }
                        iterator.remove();
                        threadPoolTaskExecutor.execute(() -> quit(myChrome));
                        log.info("destroy chrome : " + sessionId);
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    MyChromeClient client = clients.remove(myChrome.getUserTrackId());
                    if (client != null && client.getLoginType() == LoginType.QQBOT) {
                        botService.exit(Long.parseLong(myChrome.getUserTrackId()));
                    }
                    myChrome.setUserTrackId(null);
                }
                break;
            }
        }

        Iterator<Map.Entry<String, MyChromeClient>> iterator2 = clients.entrySet().iterator();
        while (iterator2.hasNext()) {
            MyChromeClient curr = iterator2.next().getValue();
            if (curr.getChromeSessionId().equals(removeChromeSessionId)) {
                try {
                    iterator2.remove();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                log.info("remove MyChromeClient : " + removeChromeSessionId);
                break;
            }
        }
    }

    @Override
    public void createChromeOptions() {
        chromeOptions = new ChromeOptions();
        chromeOptions.setExperimentalOption("excludeSwitches", new String[]{"enable-automation"});
        chromeOptions.setExperimentalOption("useAutomationExtension", false);
        chromeOptions.addArguments("lang=zh-CN,zh,zh-TW,en-US,en");
        chromeOptions.addArguments("--disable-blink-features");
        chromeOptions.addArguments("user-agent=Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4606.81 Safari/537.36");
//        chromeOptions.addArguments("user-agent=Mozilla/5.0 (iPhone; CPU iPhone OS 15_0_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.0 Mobile/15E148 Safari/604.1");
        chromeOptions.addArguments("--disable-blink-features=AutomationControlled");
        chromeOptions.addArguments("disable-blink-features=AutomationControlled");
        chromeOptions.addArguments("--disable-gpu");
        chromeOptions.setCapability("screenResolution", "1024x768x24");
        chromeOptions.setCapability("enableVideo", false);
        chromeOptions.addArguments("--lang=zh-cn");
        chromeOptions.addArguments("lang=zh_CN.UTF-8");
        chromeOptions.setCapability("enableVideo", false);
        if (chromeTimeout < 60) {
            chromeTimeout = 60;
        }
        if (headless) {
            chromeOptions.addArguments("--headless");
        }
        //ssl证书支持
        chromeOptions.setCapability("acceptSslCerts", true);
        //截屏支持
        chromeOptions.setCapability("takesScreenshot", true);
        //css搜索支持
        chromeOptions.setCapability("cssSelectorsEnabled", true);
        chromeOptions.addArguments("--no-sandbox");
        chromeOptions.addArguments("--disable-extensions");
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("credentials_enable_service", false);
        prefs.put("profile.password_manager_enabled", false);
        chromeOptions.setExperimentalOption("prefs", prefs);
        chromeOptions.addArguments("--ignore-ssl-errors=yes");
        chromeOptions.addArguments("--ignore-certificate-errors");
        chromeOptions.addArguments("--allow-running-insecure-content");
        chromeOptions.addArguments("--disable-software-rasterizer");
        chromeOptions.addArguments("--disable-dev-shm-usage");
        chromeOptions.addArguments("--window-size=500,700");
    }

    @Override
    public String getXddUrl() {
        return xddUrl;
    }

    @Override
    public String getXddToken() {
        return xddToken;
    }

    @Override
    public List<QLConfig> getQlConfigs() {
        return qlConfigs;
    }

    @Override
    public Properties getProperties() {
        return properties;
    }

    @Override
    public <T> T exec(WebDriverOpCallBack<T> executor) {
        RemoteWebDriver webDriver = null;
        try {
            webDriver = new ChromeDriver(chromeOptions);
            webDriver.manage().timeouts().implicitlyWait(10, TimeUnit.SECONDS).pageLoadTimeout(20, TimeUnit.SECONDS).setScriptTimeout(20, TimeUnit.SECONDS);
            return executor.doBusiness(webDriver);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (webDriver != null) {
                webDriver.quit();
            }
        }
        return null;
    }

    @Override
    public StatClient getStatClient() {
        int availChromeCount = 0;
        int webSessionCount = 0;
        int qqSessionCount = 0;
        int totalChromeCount = CAPACITY;
        for (MyChrome chrome : chromes.values()) {
            if (chrome.getUserTrackId() == null) {
                availChromeCount++;
            } else {
                String userTrackId = chrome.getUserTrackId();
                MyChromeClient client = clients.get(userTrackId);
                if (client != null) {
                    LoginType loginType = client.getLoginType();
                    if (loginType == LoginType.WEB) {
                        webSessionCount++;
                    } else if (loginType == LoginType.QQBOT) {
                        qqSessionCount++;
                    }
                }
            }
        }
        return new StatClient(availChromeCount, webSessionCount, qqSessionCount, totalChromeCount);
    }

    @Override
    public void close() {
        for (MyChrome myChrome : chromes.values()) {
            quit(myChrome);
        }
    }
}
