package org.tron.common.logsfilter.listener;


import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PluginEventListenerImpl implements IPluginEventListener {

    @Override
    public void setServerAddress(String address) {
        logger.info("setServerAddress ================> {}", address);
    }

    @Override
    public void setTopic(int eventType, String topic) {
        logger.info("setTopic ================> {} - {}", eventType, topic);
    }

    @Override
    public void setDBConfig(String dbConfig) {
        logger.info("setDBConfig ================> {}", dbConfig);
    }

    @Override
    public void start() {
        logger.info("================== start ================");
    }

    @Override
    public void handleBlockEvent(Object trigger) {
        logger.info("handleBlockEvent ================> {}", trigger);
    }

    @Override
    public void handleTransactionTrigger(Object trigger) {
        logger.info("handleTransactionTrigger ================> {}", trigger);

    }

    @Override
    public void handleContractLogTrigger(Object trigger) {
        logger.info("handleContractLogTrigger ================> {}", trigger);

    }

    @Override
    public void handleContractEventTrigger(Object trigger) {
        logger.info("handleContractEventTrigger ================> {}", trigger);

    }

    @Override
    public void handleSolidityTrigger(Object trigger) {
        logger.info("handleSolidityTrigger ================> {}", trigger);

    }

    @Override
    public void handleSolidityLogTrigger(Object trigger) {
        logger.info("handleSolidityLogTrigger ================> {}", trigger);

    }

    @Override
    public void handleSolidityEventTrigger(Object trigger) {
        logger.info("handleSolidityEventTrigger ================> {}", trigger);

    }
}
