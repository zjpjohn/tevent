package rocketmq;

import com.alibaba.rocketmq.common.message.Message;
import com.tongbanjie.tevent.client.ClientConfig;
import com.tongbanjie.tevent.client.MessageResult;
import com.tongbanjie.tevent.client.sender.TransactionCheckListener;
import com.tongbanjie.tevent.common.Constants;
import com.tongbanjie.tevent.rocketmq.RocketMQNotifyManager;
import com.tongbanjie.tevent.rocketmq.RocketMQParam;
import org.junit.Before;
import org.junit.Test;

/**
 * 〈一句话功能简述〉<p>
 * 〈功能详细描述〉
 *
 * @author zixiao
 * @date 16/10/27
 */
public class RocketMQTest {

    private ClientConfig clientConfig;

    @Before
    public void before() throws Exception {
        clientConfig = new ClientConfig("192.168.1.120:2181");
    }

    @Test
    public void sendMessage() throws Exception {

        RocketMQNotifyManager mqNotifyManager = create();

        for(int i=0; i< 10; i++) {
            Message message = new Message();
            message.setTopic(Constants.TEVENT_TEST_TOPIC);
            message.setKeys("cluster_msg_" + i);
            message.setBody(("RocketMQTest " + i).getBytes());

            MessageResult result = mqNotifyManager.sendMessage(message);
            if (result.isSuccess()) {
                System.out.println(">>>Send message '" + message.getKeys() + "' to server success. msgId=" + result.getMsgId());
            } else {
                System.err.println(">>>Send message '" + message.getKeys() + "' to server failed, " + result.getErrorMsg());
            }
        }

        Thread.sleep(5000<<10);

    }

    private RocketMQNotifyManager create() throws Exception {
        RocketMQParam rocketMQParam = new RocketMQParam();
        rocketMQParam.setGroupId(Constants.TEVENT_TEST_P_GROUP)
                .setName("RocketMQTest")
                .setTopic(Constants.TEVENT_TEST_TOPIC)
                .setNamesrvAddr("192.168.1.42:9876");
        TransactionCheckListener checkListener = new TestTransactionCheckListener();
        RocketMQNotifyManager mqNotifyManager = new RocketMQNotifyManager(rocketMQParam, checkListener, clientConfig);
        mqNotifyManager.init();
        return mqNotifyManager;
    }

}
