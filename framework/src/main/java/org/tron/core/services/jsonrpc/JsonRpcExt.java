package org.tron.core.services.jsonrpc;

import com.alibaba.fastjson.JSON;
import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessageV3;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.tron.api.GrpcAPI;
import org.tron.common.logsfilter.capsule.BlockFilterCapsule;
import org.tron.common.logsfilter.capsule.LogsFilterCapsule;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.ByteUtil;
import org.tron.core.Wallet;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.db.Manager;
import org.tron.core.db2.core.Chainbase;
import org.tron.core.exception.*;
import org.tron.core.services.http.JsonFormat;
import org.tron.core.services.http.Util;
import org.tron.core.services.jsonrpc.filters.*;
import org.tron.core.services.jsonrpc.types.BlockResult;
import org.tron.core.services.jsonrpc.types.BuildArguments;
import org.tron.core.services.jsonrpc.types.TransactionResult;
import org.tron.protos.Protocol;
import org.tron.protos.contract.AssetIssueContractOuterClass;
import org.tron.protos.contract.BalanceContract;
import org.tron.protos.contract.SmartContractOuterClass;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import static org.tron.core.Wallet.CONTRACT_VALIDATE_ERROR;
import static org.tron.core.Wallet.CONTRACT_VALIDATE_EXCEPTION;
import static org.tron.core.services.http.Util.setTransactionExtraData;
import static org.tron.core.services.http.Util.setTransactionPermissionId;
import static org.tron.core.services.jsonrpc.JsonRpcApiUtil.*;

@Slf4j(topic = "API")
public class JsonRpcExt {
    private final Wallet wallet;
    private final Manager manager;
    private final ExecutorService sectionExecutor;

    public static final String HASH_REGEX = "(0x)?[a-zA-Z0-9]{64}$";
    public static final String JSON_ERROR = "invalid json request";
    public static final String FILTER_NOT_FOUND = "filter not found";

    /**
     * for log filter in solidity Json-RPC
     */
    @Getter
    protected static final Map<String, LogFilterAndResult> eventFilter2ResultSolidity = new ConcurrentHashMap<>();
    /**
     * for log filter in Full Json-RPC
     */
    @Getter
    protected static final Map<String, LogFilterAndResult> eventFilter2ResultFull = new ConcurrentHashMap<>();
    /**
     * for block in Full Json-RPC
     */
    @Getter
    protected static final Map<String, BlockFilterAndResult> blockFilter2ResultFull = new ConcurrentHashMap<>();

    /**
     * for block in solidity Json-RPC
     */
    @Getter
    protected static final Map<String, BlockFilterAndResult> blockFilter2ResultSolidity = new ConcurrentHashMap<>();


    public JsonRpcExt(Wallet wallet, Manager manager) {
        this.wallet = wallet;
        this.manager = manager;
        this.sectionExecutor = Executors.newFixedThreadPool(5);
    }

    public TronJsonRpc.TransactionJson buildCreateSmartContractTransaction(byte[] ownerAddress, BuildArguments args) throws JsonRpcInvalidParamsException, JsonRpcInvalidRequestException, JsonRpcInternalException {
        try {
            SmartContractOuterClass.CreateSmartContract.Builder build = SmartContractOuterClass.CreateSmartContract.newBuilder();

            build.setOwnerAddress(ByteString.copyFrom(ownerAddress));

            build.setCallTokenValue(args.getTokenValue()).setTokenId(args.getTokenId());

            SmartContractOuterClass.SmartContract.ABI.Builder abiBuilder = SmartContractOuterClass.SmartContract.ABI.newBuilder();
            if (StringUtils.isNotEmpty(args.getAbi())) {
                String abiStr = "{" + "\"entrys\":" + args.getAbi() + "}";
                JsonFormat.merge(abiStr, abiBuilder, args.isVisible());
            }

            SmartContractOuterClass.SmartContract.Builder smartBuilder = SmartContractOuterClass.SmartContract.newBuilder();
            smartBuilder
                    .setAbi(abiBuilder)
                    .setCallValue(args.parseValue())
                    .setConsumeUserResourcePercent(args.getConsumeUserResourcePercent())
                    .setOriginEnergyLimit(args.getOriginEnergyLimit());

            smartBuilder.setOriginAddress(ByteString.copyFrom(ownerAddress));

            // bytecode + parameter
            smartBuilder.setBytecode(ByteString.copyFrom(ByteArray.fromHexString(args.getData())));

            if (StringUtils.isNotEmpty(args.getName())) {
                smartBuilder.setName(args.getName());
            }

            build.setNewContract(smartBuilder);

            Protocol.Transaction tx = wallet.createTransactionCapsule(build.build(), Protocol.Transaction.Contract.ContractType.CreateSmartContract).getInstance();
            Protocol.Transaction.Builder txBuilder = tx.toBuilder();
            Protocol.Transaction.raw.Builder rawBuilder = tx.getRawData().toBuilder();
            rawBuilder.setFeeLimit(args.parseGas() * wallet.getEnergyFee());

            txBuilder.setRawData(rawBuilder);
            tx = setTransactionPermissionId(args.getPermissionId(), txBuilder.build());

            TronJsonRpc.TransactionJson transactionJson = new TronJsonRpc.TransactionJson();
            transactionJson.setTransaction(JSON.parseObject(Util.printCreateTransaction(tx, false)));

            return transactionJson;
        } catch (JsonRpcInvalidParamsException e) {
            throw new JsonRpcInvalidParamsException(e.getMessage());
        } catch (ContractValidateException e) {
            throw new JsonRpcInvalidRequestException(e.getMessage());
        } catch (Exception e) {
            throw new JsonRpcInternalException(e.getMessage());
        }
    }

    // from and to should not be null
    public TronJsonRpc.TransactionJson buildTriggerSmartContractTransaction(byte[] ownerAddress, BuildArguments args) throws JsonRpcInvalidParamsException, JsonRpcInvalidRequestException, JsonRpcInternalException {
        byte[] contractAddress = addressCompatibleToByteArray(args.getTo());

        SmartContractOuterClass.TriggerSmartContract.Builder build = SmartContractOuterClass.TriggerSmartContract.newBuilder();
        GrpcAPI.TransactionExtention.Builder trxExtBuilder = GrpcAPI.TransactionExtention.newBuilder();
        GrpcAPI.Return.Builder retBuilder = GrpcAPI.Return.newBuilder();

        try {

            build.setOwnerAddress(ByteString.copyFrom(ownerAddress)).setContractAddress(ByteString.copyFrom(contractAddress));

            if (StringUtils.isNotEmpty(args.getData())) {
                build.setData(ByteString.copyFrom(ByteArray.fromHexString(args.getData())));
            } else {
                build.setData(ByteString.copyFrom(new byte[0]));
            }

            build.setCallTokenValue(args.getTokenValue())
                    .setTokenId(args.getTokenId())
                    .setCallValue(args.parseValue());

            Protocol.Transaction tx = wallet.createTransactionCapsule(build.build(), Protocol.Transaction.Contract.ContractType.TriggerSmartContract).getInstance();

            Protocol.Transaction.Builder txBuilder = tx.toBuilder();
            Protocol.Transaction.raw.Builder rawBuilder = tx.getRawData().toBuilder();
            rawBuilder.setFeeLimit(args.parseGas() * wallet.getEnergyFee());
            txBuilder.setRawData(rawBuilder);

            Protocol.Transaction trx = wallet.triggerContract(build.build(), new TransactionCapsule(txBuilder.build()), trxExtBuilder, retBuilder);
            trx = setTransactionPermissionId(args.getPermissionId(), trx);
            trxExtBuilder.setTransaction(trx);
        } catch (JsonRpcInvalidParamsException e) {
            throw new JsonRpcInvalidParamsException(e.getMessage());
        } catch (ContractValidateException e) {
            throw new JsonRpcInvalidRequestException(e.getMessage());
        } catch (Exception e) {
            String errString = JSON_ERROR;
            if (e.getMessage() != null) {
                errString = e.getMessage().replaceAll("[\"]", "'");
            }

            throw new JsonRpcInternalException(errString);
        }

        String jsonString = Util.printTransaction(trxExtBuilder.build().getTransaction(),
                args.isVisible());
        TronJsonRpc.TransactionJson transactionJson = new TronJsonRpc.TransactionJson();
        transactionJson.setTransaction(JSON.parseObject(jsonString));

        return transactionJson;
    }

    public TronJsonRpc.TransactionJson createTransactionJson(GeneratedMessageV3.Builder<?> build, Protocol.Transaction.Contract.ContractType contractTyp, BuildArguments args) throws JsonRpcInvalidRequestException, JsonRpcInternalException {
        try {
            Protocol.Transaction tx = wallet
                    .createTransactionCapsule(build.build(), contractTyp)
                    .getInstance();
            tx = setTransactionPermissionId(args.getPermissionId(), tx);
            tx = setTransactionExtraData(args.getExtraData(), tx, args.isVisible());

            TronJsonRpc.TransactionJson transactionJson = new TronJsonRpc.TransactionJson();
            transactionJson.setTransaction(JSON.parseObject(Util.printCreateTransaction(tx, args.isVisible())));

            return transactionJson;
        } catch (ContractValidateException e) {
            throw new JsonRpcInvalidRequestException(e.getMessage());
        } catch (Exception e) {
            throw new JsonRpcInternalException(e.getMessage());
        }
    }

    public TronJsonRpc.TransactionJson buildTransferContractTransaction(byte[] ownerAddress, BuildArguments args) throws JsonRpcInvalidParamsException, JsonRpcInvalidRequestException, JsonRpcInternalException {
        long amount = args.parseValue();

        BalanceContract.TransferContract.Builder build = BalanceContract.TransferContract.newBuilder();
        build.setOwnerAddress(ByteString.copyFrom(ownerAddress))
                .setToAddress(ByteString.copyFrom(addressCompatibleToByteArray(args.getTo())))
                .setAmount(amount);

        return createTransactionJson(build, Protocol.Transaction.Contract.ContractType.TransferContract, args);
    }

    // tokenId and tokenValue should not be null
    public TronJsonRpc.TransactionJson buildTransferAssetContractTransaction(byte[] ownerAddress, BuildArguments args) throws JsonRpcInvalidParamsException, JsonRpcInvalidRequestException, JsonRpcInternalException {
        byte[] tokenIdArr = ByteArray.fromString(String.valueOf(args.getTokenId()));
        if (tokenIdArr == null) {
            throw new JsonRpcInvalidParamsException("invalid param value: invalid tokenId");
        }

        AssetIssueContractOuterClass.TransferAssetContract.Builder build = AssetIssueContractOuterClass.TransferAssetContract.newBuilder();
        build.setOwnerAddress(ByteString.copyFrom(ownerAddress))
                .setToAddress(ByteString.copyFrom(addressCompatibleToByteArray(args.getTo())))
                .setAssetName(ByteString.copyFrom(tokenIdArr))
                .setAmount(args.getTokenValue());

        return createTransactionJson(build, Protocol.Transaction.Contract.ContractType.TransferAssetContract, args);
    }

    public TronJsonRpcImpl.RequestSource getSource() {
        Chainbase.Cursor cursor = wallet.getCursor();
        switch (cursor) {
            case SOLIDITY:
                return TronJsonRpcImpl.RequestSource.SOLIDITY;
            case PBFT:
                return TronJsonRpcImpl.RequestSource.PBFT;
            default:
                return TronJsonRpcImpl.RequestSource.FULLNODE;
        }
    }

    public void disableInPBFT(String method) throws JsonRpcMethodNotFoundException {
        if (getSource() == TronJsonRpcImpl.RequestSource.PBFT) {
            String msg = String.format("the method %s does not exist/is not available in PBFT", method);
            throw new JsonRpcMethodNotFoundException(msg);
        }
    }

    public TronJsonRpc.LogFilterElement[] getLogsByLogFilterWrapper(LogFilterWrapper logFilterWrapper, long currentMaxBlockNum)
            throws JsonRpcTooManyResultException, ExecutionException, InterruptedException, BadItemException, ItemNotFoundException {
        //query possible block
        LogBlockQuery logBlockQuery = new LogBlockQuery(logFilterWrapper, manager.getChainBaseManager().getSectionBloomStore(), currentMaxBlockNum, sectionExecutor);
        List<Long> possibleBlockList = logBlockQuery.getPossibleBlock();

        //match event from block one by one exactly
        LogMatch logMatch = new LogMatch(logFilterWrapper, possibleBlockList, manager);
        return logMatch.matchBlockOneByOne();
    }

    public static Object[] getFilterResult(String filterId, Map<String, BlockFilterAndResult>
            blockFilter2Result, Map<String, LogFilterAndResult> eventFilter2Result)
            throws ItemNotFoundException {
        Object[] result;

        if (blockFilter2Result.containsKey(filterId)) {
            List<String> blockHashList = blockFilter2Result.get(filterId).popAll();
            result = blockHashList.toArray(new String[blockHashList.size()]);
            blockFilter2Result.get(filterId).updateExpireTime();

        } else if (eventFilter2Result.containsKey(filterId)) {
            List<TronJsonRpc.LogFilterElement> logElementList = eventFilter2Result.get(filterId).popAll();
            result = logElementList.toArray(new TronJsonRpc.LogFilterElement[0]);
            eventFilter2Result.get(filterId).updateExpireTime();

        } else {
            throw new ItemNotFoundException(FILTER_NOT_FOUND);
        }

        return result;
    }


    public TransactionResult formatTransactionResult(Protocol.TransactionInfo transactioninfo, Protocol.Block block) {
        String txId = ByteArray.toHexString(transactioninfo.getId().toByteArray());

        Protocol.Transaction transaction = null;
        int transactionIndex = -1;

        List<Protocol.Transaction> txList = block.getTransactionsList();
        for (int index = 0; index < txList.size(); index++) {
            transaction = txList.get(index);
            if (getTxID(transaction).equals(txId)) {
                transactionIndex = index;
                break;
            }
        }

        if (transactionIndex == -1) {
            return null;
        }

        long energyUsageTotal = transactioninfo.getReceipt().getEnergyUsageTotal();
        BlockCapsule blockCapsule = new BlockCapsule(block);
        return new TransactionResult(blockCapsule, transactionIndex, transaction, energyUsageTotal, wallet.getEnergyFee(blockCapsule.getTimeStamp()), wallet);
    }

    public TransactionResult getTransactionByBlockAndIndex(Protocol.Block block, String index) throws JsonRpcInvalidParamsException {
        int txIndex;
        try {
            txIndex = ByteArray.jsonHexToInt(index);
        } catch (Exception e) {
            throw new JsonRpcInvalidParamsException("invalid index value");
        }

        if (txIndex >= block.getTransactionsCount()) {
            return null;
        }

        Protocol.Transaction transaction = block.getTransactions(txIndex);
        long energyUsageTotal = getEnergyUsageTotal(transaction, wallet);
        BlockCapsule blockCapsule = new BlockCapsule(block);

        return new TransactionResult(blockCapsule, txIndex, transaction, energyUsageTotal, wallet.getEnergyFee(blockCapsule.getTimeStamp()), wallet);
    }

    public void callTriggerConstantContract(byte[] ownerAddressByte, byte[] contractAddressByte, long value, byte[] data, GrpcAPI.TransactionExtention.Builder trxExtBuilder, GrpcAPI.Return.Builder retBuilder) throws ContractValidateException, ContractExeException, HeaderNotFound, VMIllegalException {

        SmartContractOuterClass.TriggerSmartContract triggerContract = triggerCallContract(
                ownerAddressByte,
                contractAddressByte,
                value,
                data,
                0,
                null
        );

        TransactionCapsule trxCap = wallet.createTransactionCapsule(triggerContract, Protocol.Transaction.Contract.ContractType.TriggerSmartContract);
        Protocol.Transaction trx = wallet.triggerConstantContract(triggerContract, trxCap, trxExtBuilder, retBuilder);

        trxExtBuilder.setTransaction(trx);
        trxExtBuilder.setTxid(trxCap.getTransactionId().getByteString());
        trxExtBuilder.setResult(retBuilder);
        retBuilder.setResult(true).setCode(GrpcAPI.Return.response_code.SUCCESS);
    }

    /**
     * @param data Hash of the method signature and encoded parameters. for example:
     *             getMethodSign(methodName(uint256,uint256)) || data1 || data2
     */
    public String call(byte[] ownerAddressByte, byte[] contractAddressByte, long value, byte[] data) {

        GrpcAPI.TransactionExtention.Builder trxExtBuilder = GrpcAPI.TransactionExtention.newBuilder();
        GrpcAPI.Return.Builder retBuilder = GrpcAPI.Return.newBuilder();
        GrpcAPI.TransactionExtention trxExt;

        try {
            callTriggerConstantContract(ownerAddressByte, contractAddressByte, value, data, trxExtBuilder, retBuilder);

        } catch (ContractValidateException | VMIllegalException e) {
            retBuilder.setResult(false).setCode(GrpcAPI.Return.response_code.CONTRACT_VALIDATE_ERROR).setMessage(ByteString.copyFromUtf8(CONTRACT_VALIDATE_ERROR + e.getMessage()));
            trxExtBuilder.setResult(retBuilder);
            logger.warn(CONTRACT_VALIDATE_EXCEPTION, e.getMessage());
        } catch (RuntimeException e) {
            retBuilder.setResult(false).setCode(GrpcAPI.Return.response_code.CONTRACT_EXE_ERROR).setMessage(ByteString.copyFromUtf8(e.getClass() + " : " + e.getMessage()));
            trxExtBuilder.setResult(retBuilder);
            logger.warn("When run constant call in VM, have RuntimeException: " + e.getMessage());
        } catch (Exception e) {
            retBuilder.setResult(false).setCode(GrpcAPI.Return.response_code.OTHER_ERROR).setMessage(ByteString.copyFromUtf8(e.getClass() + " : " + e.getMessage()));
            trxExtBuilder.setResult(retBuilder);
            logger.warn("Unknown exception caught: " + e.getMessage(), e);
        } finally {
            trxExt = trxExtBuilder.build();
        }

        String result = "0x";
        String code = trxExt.getResult().getCode().toString();
        if ("SUCCESS".equals(code)) {
            List<ByteString> list = trxExt.getConstantResultList();
            byte[] listBytes = new byte[0];
            for (ByteString bs : list) {
                listBytes = ByteUtil.merge(listBytes, bs.toByteArray());
            }
            result = ByteArray.toJsonHex(listBytes);
        } else {
            logger.error("trigger contract failed.");
        }

        return result;
    }


    public static void handleBLockFilter(BlockFilterCapsule blockFilterCapsule) {
        Iterator<Map.Entry<String, BlockFilterAndResult>> it;

        if (blockFilterCapsule.isSolidified()) {
            it = getBlockFilter2ResultSolidity().entrySet().iterator();
        } else {
            it = getBlockFilter2ResultFull().entrySet().iterator();
        }

        while (it.hasNext()) {
            Map.Entry<String, BlockFilterAndResult> entry = it.next();
            if (entry.getValue().isExpire()) {
                it.remove();
                continue;
            }
            entry.getValue().getResult().add(ByteArray.toJsonHex(blockFilterCapsule.getBlockHash()));
        }
    }

    public static void handleLogsFilter(LogsFilterCapsule logsFilterCapsule) {
        Iterator<Map.Entry<String, LogFilterAndResult>> it;

        if (logsFilterCapsule.isSolidified()) {
            it = getEventFilter2ResultSolidity().entrySet().iterator();
        } else {
            it = getEventFilter2ResultFull().entrySet().iterator();
        }

        while (it.hasNext()) {
            Map.Entry<String, LogFilterAndResult> entry = it.next();
            if (entry.getValue().isExpire()) {
                it.remove();
                continue;
            }

            LogFilterAndResult logFilterAndResult = entry.getValue();
            long fromBlock = logFilterAndResult.getLogFilterWrapper().getFromBlock();
            long toBlock = logFilterAndResult.getLogFilterWrapper().getToBlock();
            if (!(fromBlock <= logsFilterCapsule.getBlockNumber()
                    && logsFilterCapsule.getBlockNumber() <= toBlock)) {
                continue;
            }

            if (logsFilterCapsule.getBloom() != null
                    && !logFilterAndResult.getLogFilterWrapper().getLogFilter()
                    .matchBloom(logsFilterCapsule.getBloom())) {
                continue;
            }

            LogFilter logFilter = logFilterAndResult.getLogFilterWrapper().getLogFilter();
            List<TronJsonRpc.LogFilterElement> elements =
                    LogMatch.matchBlock(logFilter, logsFilterCapsule.getBlockNumber(),
                            logsFilterCapsule.getBlockHash(), logsFilterCapsule.getTxInfoList(),
                            logsFilterCapsule.isRemoved());
            if (CollectionUtils.isNotEmpty(elements)) {
                logFilterAndResult.getResult().addAll(elements);
            }
        }
    }

    public byte[] hashToByteArray(String hash) throws JsonRpcInvalidParamsException {
        if (!Pattern.matches(HASH_REGEX, hash)) {
            throw new JsonRpcInvalidParamsException("invalid hash value");
        }

        byte[] bHash;
        try {
            bHash = ByteArray.fromHexString(hash);
        } catch (Exception e) {
            throw new JsonRpcInvalidParamsException(e.getMessage());
        }
        return bHash;
    }

    public Protocol.Block getBlockByJsonHash(String blockHash) throws JsonRpcInvalidParamsException {
        byte[] bHash = hashToByteArray(blockHash);
        return wallet.getBlockById(ByteString.copyFrom(bHash));
    }

    public BlockResult getBlockResult(Protocol.Block block, boolean fullTx) {
        if (block == null) {
            return null;
        }

        return new BlockResult(block, fullTx, wallet);
    }
}
