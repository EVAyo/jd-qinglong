package com.meread.selenium.ws;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.meread.selenium.bean.qq.GroupMessage;
import com.meread.selenium.bean.qq.PrivateMessage;
import com.meread.selenium.service.BaseWebDriverManager;
import com.meread.selenium.service.BotService;
import com.meread.selenium.util.CommonAttributes;
import com.meread.selenium.ws.qqbot.ProcessStatus;
import com.meread.selenium.ws.qqbot.QA;
import com.meread.selenium.ws.qqbot.QCommand;
import com.meread.selenium.ws.qqbot.QQAiFlow;
import com.meread.selenium.ws.qqbot.processor.QGetNewCkProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

@Component
@Slf4j
public class QQEventHandler extends TextWebSocketHandler {

    @Autowired
    private BotService botService;

    @Autowired
    private BaseWebDriverManager driverManager;

    @Autowired
    private QGetNewCkProcessor getNewCkProcessor;

    @Value("${go-cqhttp.dir}")
    private String goCqHttpDir;

    /**
     * socket 建立成功事件
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String webSocketSessionId = session.getId();
        log.info("afterConnectionEstablished " + webSocketSessionId);
        CommonAttributes.webSocketSession = session;
    }

    /**
     * socket 断开连接时
     *
     * @param session
     * @param status
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String webSocketSessionId = session.getId();
        log.info("afterConnectionClosed " + webSocketSessionId + ", CloseStatus" + status);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if (goCqHttpDir.endsWith("/")) {
            goCqHttpDir = goCqHttpDir.substring(0, goCqHttpDir.length() - 1);
        }
        File configPath = new File(goCqHttpDir + "/config.yml");
        if (!configPath.exists()) {
            log.warn(goCqHttpDir + "/config.yml文件不存在");
            return;
        }
        YamlPropertiesFactoryBean yamlFactory = new YamlPropertiesFactoryBean();
        yamlFactory.setResources(new FileSystemResource(configPath));
        Properties props = yamlFactory.getObject();
        String selfQQ = props.getProperty("account.uin", "0");
        String selfGroup = driverManager.getProperties().getProperty("MONITOR.QQ.GROUPID", "");

        String payload = message.getPayload();
        JSONObject jsonObject = JSON.parseObject(payload);
        String post_type = jsonObject.getString("post_type");
        String message_type = jsonObject.getString("message_type");
        if (!"message".equals(post_type)) {
            return;
        }

        log.info("payload = " + payload);

        String content;
        long senderQQ;

        JSONObject jo = new JSONObject();
        jo.put("action", "send_private_msg");
        jo.put("echo", UUID.randomUUID().toString().replaceAll("-", ""));
        JSONObject params = new JSONObject();
        jo.put("params", params);

        if ("group".equals(message_type)) {
            //群聊消息
            //处理私聊消息
            GroupMessage groupMessage = JSON.parseObject(payload, GroupMessage.class);
            content = groupMessage.getMessage();
            senderQQ = groupMessage.getUser_id();
            long group_id = groupMessage.getGroup_id();
            if (!selfGroup.equals(String.valueOf(group_id))) {
                log.info("请配置MONITOR.QQ.GROUPID=qq群号，接收群是" + selfGroup);
                return;
            }
            params.put("group_id", group_id);
        } else if ("private".equals(message_type)) {
            //私聊消息
            PrivateMessage privateMessage = JSON.parseObject(payload, PrivateMessage.class);
            content = privateMessage.getMessage();
            senderQQ = privateMessage.getUser_id();
            long self_id = privateMessage.getSelf_id();
            if (!selfQQ.equals(String.valueOf(self_id))) {
                log.info(goCqHttpDir + "/config.yml配置的qq号，不是此消息的接收人，接收人是" + self_id);
                return;
            }
        } else {
            log.info("不支持的消息类型");
            return;
        }

        if (StringUtils.isEmpty(content)) {
            return;
        }

        if ("help".equals(content) || "h".equals(content) || String.valueOf(QCommand.HELP.getCode()).equals(content)) {
            botService.sendHelpMsg(senderQQ);
            return;
        } else if ("quit".equals(content) || "q".equals(content) || String.valueOf(QCommand.EXIT.getCode()).equals(content)) {
            botService.exit(senderQQ);
            return;
        } else if (String.valueOf(QCommand.QL_STATUS.getCode()).equals(content)) {
            String qlStatus = botService.getQLStatus(false);
            botService.sendMsgWithRetry(senderQQ, qlStatus);
            return;
        }

        QQAiFlow qqAiFlow = botService.getQqAiFlowMap().get(senderQQ);
        QCommand topCommand = null;
        if (qqAiFlow != null) {
            log.info("会话未关闭,查找上次会话进度");
            QA qa = qqAiFlow.getLast();
            if (qa == null) {
                log.info("会话异常结束");
                botService.getQqAiFlowMap().remove(senderQQ);
                botService.sendMsgWithRetry(senderQQ, "会话异常结束");
                return;
            }
            topCommand = qa.getTopCommand();
        } else {

            boolean contains = QCommand.getCodeList().contains(content);
            if (!contains) {
                if ("private".equals(message_type)) {
                    botService.sendMsgWithRetry(senderQQ, "不支持的指令");
                }
                return;
            }

            qqAiFlow = new QQAiFlow();
            qqAiFlow.setSenderQQ(senderQQ);
            List<QA> qas = new ArrayList<>();
            QA qa = new QA();
            int code = 0;
            try {
                code = Integer.parseInt(content);
            } catch (NumberFormatException e) {
                botService.sendMsgWithRetry(senderQQ, "输入有误，请输入数字");
                return;
            }
            topCommand = QCommand.parse(code);

            if (topCommand == null || topCommand.getParentCode() != 0) {
                if ("private".equals(message_type)) {
                    botService.sendMsgWithRetry(senderQQ, "输入有误");
                }
                return;
            }

            qa.setRequestRaw(content);
            qa.setRequestTime(System.currentTimeMillis());
            qa.setStatus(ProcessStatus.PROCESSING);
            qa.setQCommand(topCommand);
            qas.add(qa);
            qqAiFlow.setQas(qas);
        }

        if (topCommand == QCommand.GET_NEW_CK) {
            try {
                getNewCkProcessor.process(senderQQ, content, qqAiFlow);
            } catch (Exception e) {
                e.printStackTrace();
                if (e instanceof NumberFormatException) {
                    botService.sendMsgWithRetry(senderQQ, "输入有误");
                }
            }
            botService.getQqAiFlowMap().put(senderQQ, qqAiFlow);
        }
//            int max = 8, min = 1;
//            int random = (int) (Math.random() * (max - min) + min);
//            Thread.sleep(random * 300L);
//            session.sendMessage(new TextMessage(jo.toJSONString()));
    }
}