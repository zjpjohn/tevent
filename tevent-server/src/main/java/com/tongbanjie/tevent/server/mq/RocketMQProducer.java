package com.tongbanjie.tevent.server.mq;

import com.alibaba.rocketmq.client.exception.MQClientException;
import com.alibaba.rocketmq.client.producer.DefaultMQProducer;
import com.alibaba.rocketmq.client.producer.MQProducer;
import com.alibaba.rocketmq.client.producer.SendResult;
import com.alibaba.rocketmq.common.message.Message;
import com.tongbanjie.tevent.common.message.MQType;
import com.tongbanjie.tevent.common.message.SendStatus;
import com.tongbanjie.tevent.common.message.TransactionState;
import com.tongbanjie.tevent.common.message.RocketMQMessage;
import com.tongbanjie.tevent.rpc.protocol.ResponseCode;
import com.tongbanjie.tevent.rpc.protocol.RpcCommand;
import com.tongbanjie.tevent.rpc.protocol.RpcCommandBuilder;
import com.tongbanjie.tevent.common.body.RocketMQBody;
import com.tongbanjie.tevent.rpc.protocol.header.MessageResultHeader;
import com.tongbanjie.tevent.server.ServerController;
import com.tongbanjie.tevent.store.Result;
import com.tongbanjie.tevent.store.service.RocketMQStoreService;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * RocketMQ发送者<p>
 * 〈功能详细描述〉
 *
 * @author zixiao
 * @date 16/9/30
 */
public class RocketMQProducer implements EventProducer {

    private static final Logger LOGGER = LoggerFactory.getLogger(RocketMQProducer.class);

    private final Map<String/* Group */, MQProducer> producerTable = new ConcurrentHashMap<String, MQProducer>();

    private final Lock lock = new ReentrantLock();

    private final RocketMQStoreService mQStoreService;

    private final ServerController serverController;

    private final String namesrvAddr;

    public RocketMQProducer(ServerController serverController) {
        this.serverController = serverController;
        this.mQStoreService = (RocketMQStoreService)this.serverController.getStoreManager().getMQStoreService(MQType.ROCKET_MQ);
        this.namesrvAddr = this.serverController.getServerConfig().getRocketMQNamesrv();
    }

    @Override
    public RpcCommand sendMessage(ChannelHandlerContext ctx, RpcCommand request) {
        RpcCommand response;
        final RocketMQBody mqBody = request.getBody(RocketMQBody.class);

        String producerAddress = null;
        if(ctx.channel() != null && ctx.channel().remoteAddress() != null){
            producerAddress = ctx.channel().remoteAddress().toString();
        }

        RocketMQMessage mqMessage = RocketMQMessage.build(mqBody, TransactionState.PREPARE, producerAddress);

        SendResult sendResult = doSend(mqMessage);
        if(sendResult != null){
            MessageResultHeader responseHeader = new MessageResultHeader();
            responseHeader.setMsgId(sendResult.getMsgId());
            response = RpcCommandBuilder.buildSuccess(responseHeader);
            LOGGER.info("发送消息 messageKey:" + mqBody.getMessageKey()
                    + ", result:" + sendResult.getSendStatus()
                    + ", msgId:"+sendResult.getMsgId());
        }else{
            response = RpcCommandBuilder.buildFail("发送消息失败");
        }
        return response;
    }

    /**
     * 发送消息方法
     * @param mqMessage
     * @return
     */
    private SendResult doSend(RocketMQMessage mqMessage){
        MQProducer producer = null;
        try {
            producer = getMQProducer(mqMessage.getProducerGroup());
        } catch (MQClientException e) {
            LOGGER.error("Find mq producer failed, group {}" + mqMessage.getProducerGroup(), e);
            return null;
        }

        Message msg = new Message(  mqMessage.getTopic(),       // topic
                                    mqMessage.getTags(),        // tag
                                    mqMessage.getMessageBody()  // body
        );
        msg.setKeys(mqMessage.getMessageKey());
        try {
            SendResult sendResult = producer.send(msg);
            LOGGER.info("Send status {}, msgId {}", sendResult.getSendStatus(), sendResult.getMsgId());
            return sendResult;
        } catch (Exception e) {
            LOGGER.error("Send to rocketMQ failed.", e);
            return null;
        }
    }

    @Override
    public RpcCommand prepareMessage(ChannelHandlerContext ctx, RpcCommand request) {
        final RpcCommand response;

        final RocketMQBody mqBody = request.getBody(RocketMQBody.class);

        String producerAddress = null;
        if(ctx.channel() != null && ctx.channel().remoteAddress() != null){
            producerAddress = ctx.channel().remoteAddress().toString();
        }

        RocketMQMessage mqMessage = RocketMQMessage.build(mqBody, TransactionState.PREPARE, producerAddress);

        //持久化 消息
        Result<Long> putResult = mQStoreService.put(mqMessage);
        if(putResult.isSuccess()){
            Long transactionId = putResult.getData();

            LOGGER.debug("准备事务消息 topic:{}, messageKey:{}, transactionId:{}" ,
                    mqMessage.getTopic(), mqMessage.getMessageKey(), transactionId);

            MessageResultHeader responseHeader = new MessageResultHeader();
            responseHeader.setTransactionId(transactionId);

            response = RpcCommandBuilder.buildSuccess(responseHeader);

        }else{
            LOGGER.error("准备事务消息失败, topic:{}, messageKey:{}, error:{}",
                    mqMessage.getTopic(), mqMessage.getMessageKey(), putResult.getErrorString());
            response = RpcCommandBuilder.buildFail("准备事务消息失败," + putResult.getErrorString());
        }

        return response;
    }

    @Override
    public RpcCommand commitMessage(ChannelHandlerContext ctx, RpcCommand request, Long transactionId) {
        final RpcCommand response;
        final RocketMQBody newMqBody = request.getBody(RocketMQBody.class);

        Result<RocketMQMessage> getResult = mQStoreService.get(transactionId);
        if(getResult.isSuccess()){
            RocketMQMessage mqMessage = getResult.getData();
            if(mqMessage == null){
                response = RpcCommandBuilder.buildResponse(ResponseCode.NOT_EXIST, "该事务消息不存在");
                LOGGER.warn("提交事务消息失败, 该消息不存在, transactionId:" + transactionId);
            }else if(mqMessage.getTransactionState() == TransactionState.COMMIT.getCode()) {
                response = RpcCommandBuilder.buildSuccess();
            }else{
                RocketMQMessage forUpdate = new RocketMQMessage();
                forUpdate.setId(transactionId);
                forUpdate.setTransactionState(TransactionState.COMMIT.getCode());
                if(newMqBody !=null && newMqBody.getMessageBody() != null){
                    //更新消息体
                    forUpdate.setMessageBody(newMqBody.getMessageBody());
                }

                //TODO x锁更新
                // update transaction_message set transactionState=COMMIT where id=#{transactionId} and transactionState=PREPARE
                Result<RocketMQMessage> commitResult = mQStoreService.update(transactionId, forUpdate);

                if(commitResult.isSuccess()){
                    LOGGER.debug("提交事务消息 topic:{}, messageKey:{}, transactionId:{}",
                            mqMessage.getTopic(), mqMessage.getMessageKey(), transactionId);
                    response = RpcCommandBuilder.buildSuccess();  
                }else{
                    LOGGER.error("提交事务消息失败, transactionId: " + transactionId+", error: "+ commitResult.getErrorString());
                    response = RpcCommandBuilder.buildFail("提交事务消息失败," + commitResult.getErrorString());
                }
                //异步发送消息
                sendMessageAsync(mqMessage, transactionId);
            }
        }else{
            LOGGER.error("提交事务消息失败, transactionId: " + transactionId+", error: "+ getResult.getErrorString());
            response = RpcCommandBuilder.buildFail("提交事务消息失败," + getResult.getErrorString());
        }
        return response;
    }

    /**
     *  异步发送消息，并记录发送状态
     * @param mqMessage
     * @param transactionId
     */
    private void sendMessageAsync(final RocketMQMessage mqMessage, final Long transactionId){
        Runnable sendTask = new Runnable() {
            @Override
            public void run() {
                SendResult sendResult = doSend(mqMessage);
                RocketMQMessage forUpdate = new RocketMQMessage();
                forUpdate.setId(transactionId);
                if(sendResult != null){
                    forUpdate.setSendStatus(SendStatus.SUCCESS.ordinal());
                    forUpdate.setMessageId(sendResult.getMsgId());
                }else{
                    forUpdate.setSendStatus(SendStatus.FAILED.ordinal());
                }
                mQStoreService.update(transactionId, forUpdate);
            }
        };
        this.serverController.getSendMessageExecutor().submit(sendTask);
    }


    @Override
    public RpcCommand rollbackMessage(ChannelHandlerContext ctx, RpcCommand request, Long transactionId) {
        final RpcCommand response;
        Result<RocketMQMessage> getResult = mQStoreService.get(transactionId);
        if(getResult.isSuccess()){
            RocketMQMessage mqMessage = getResult.getData();
            if(mqMessage == null){
                response = RpcCommandBuilder.buildResponse(ResponseCode.NOT_EXIST, "该事务消息不存在");
                LOGGER.warn("事务消息回滚失败, 消息不存在, transactionId:" + transactionId);
            }else if(mqMessage.getTransactionState() == TransactionState.PREPARE.getCode()){
                RocketMQMessage forUpdate = new RocketMQMessage();
                forUpdate.setId(transactionId);
                forUpdate.setTransactionState(TransactionState.ROLLBACK.getCode());

                //TODO x锁更新
                // update transaction_message set transactionState=ROLLBACK where id=#{transactionId} and transactionState=PREPARE
                Result<RocketMQMessage> rollbackResult = mQStoreService.update(transactionId, forUpdate);

                if(rollbackResult.isSuccess()){
                    LOGGER.debug("回滚事务消息 topic:{}, messageKey:{}, transactionId:{}" ,
                            mqMessage.getTopic(), mqMessage.getMessageKey(), transactionId);
                    response = RpcCommandBuilder.buildSuccess();
                }else{
                    LOGGER.error("事务消息回滚失败, transactionId: " + transactionId+", error: "+ rollbackResult.getErrorString());
                    response = RpcCommandBuilder.buildFail("事务消息回滚失败," + rollbackResult.getErrorString());
                }

            }else{
                response = RpcCommandBuilder.buildSuccess();
            }
        }else{
            LOGGER.error("事务消息回滚失败, transactionId: " + transactionId+", error: "+ getResult.getErrorString());
            response = RpcCommandBuilder.buildFail("事务消息回滚失败," + getResult.getErrorString());
        }
        return response;
    }

    @Override
    public RpcCommand unknownMessage(ChannelHandlerContext ctx, RpcCommand request, Long transactionId) {
        final RpcCommand response;
        Result<RocketMQMessage> getResult = mQStoreService.get(transactionId);
        if(getResult.isSuccess()){
            RocketMQMessage mqMessage = getResult.getData();
            if(mqMessage == null){
                response = RpcCommandBuilder.buildResponse(ResponseCode.NOT_EXIST, "该事务消息不存在");
                LOGGER.warn("事务消息状态更新失败, 消息不存在, transactionId:" + transactionId);
            }else if(mqMessage.getTransactionState() == TransactionState.PREPARE.getCode()){
                RocketMQMessage forUpdate = new RocketMQMessage();
                forUpdate.setId(transactionId);
                forUpdate.setRetryTimes(mqMessage.getRetryTimes()+1);

                Result<RocketMQMessage> updateResult = mQStoreService.update(transactionId, forUpdate);

                if(updateResult.isSuccess()){
                    LOGGER.debug("事务消息状态更新 topic:{}, messageKey:{}, transactionId:{}" ,
                            mqMessage.getTopic(), mqMessage.getMessageKey(), transactionId);
                    response = RpcCommandBuilder.buildSuccess();
                }else{
                    LOGGER.error("事务消息状态更新失败, transactionId: " + transactionId+", error: "+ updateResult.getErrorString());
                    response = RpcCommandBuilder.buildFail("事务消息状态更新失败," + updateResult.getErrorString());
                }
            }else{
                response = RpcCommandBuilder.buildSuccess();
            }
        }else{
            LOGGER.error("事务消息状态更新失败, transactionId: " + transactionId+", error: "+ getResult.getErrorString());
            response = RpcCommandBuilder.buildFail("事务消息状态更新失败," + getResult.getErrorString());
        }
        return response;
    }

    private MQProducer getMQProducer(String group) throws MQClientException {
        MQProducer mqProducer = producerTable.get(group);
        if (mqProducer != null) {
            return mqProducer;
        }
        try {
            lock.lock();

            mqProducer = producerTable.get(group);
            if (mqProducer != null) {
                return mqProducer;
            }

            DefaultMQProducer producer = new DefaultMQProducer(group);
            producer.setNamesrvAddr(namesrvAddr);
            producer.start();

            producerTable.put(group, producer);

            return producer;
        } finally {
            lock.unlock();
        }
    }

}
