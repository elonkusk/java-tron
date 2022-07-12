package org.tron.core.services.jsonrpc;

import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.tron.api.GrpcAPI.BytesMessage;
import org.tron.api.GrpcAPI.Return;
import org.tron.api.GrpcAPI.TransactionExtention;
import org.tron.common.crypto.Hash;
import org.tron.common.runtime.vm.DataWord;
import org.tron.common.utils.ByteArray;
import org.tron.core.Wallet;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.capsule.utils.TransactionUtil;
import org.tron.core.db.Manager;
import org.tron.core.exception.*;
import org.tron.core.services.NodeInfoService;
import org.tron.core.services.http.Util;
import org.tron.core.services.jsonrpc.filters.BlockFilterAndResult;
import org.tron.core.services.jsonrpc.filters.LogFilterAndResult;
import org.tron.core.services.jsonrpc.filters.LogFilterWrapper;
import org.tron.core.services.jsonrpc.types.*;
import org.tron.core.store.StorageRowStore;
import org.tron.core.vm.program.Storage;
import org.tron.program.Version;
import org.tron.protos.Protocol.Account;
import org.tron.protos.Protocol.Block;
import org.tron.protos.Protocol.Transaction;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.Protocol.TransactionInfo;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract;
import org.tron.protos.contract.SmartContractOuterClass.SmartContractDataWrapper;
import org.web3j.crypto.SignedRawTransaction;
import org.web3j.crypto.TransactionDecoder;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.utils.Numeric;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.tron.core.services.jsonrpc.JsonRpcApiUtil.*;

@Slf4j(topic = "API")
public class TronJsonRpcImpl extends JsonRpcExt implements TronJsonRpc {

    public enum RequestSource {
        FULLNODE,
        SOLIDITY,
        PBFT
    }

    public static final int EXPIRE_SECONDS = 5 * 60;


    public static final String EARLIEST_STR = "earliest";
    public static final String PENDING_STR = "pending";
    public static final String LATEST_STR = "latest";

    private static final String BLOCK_NUM_ERROR = "invalid block number";
    private static final String TAG_NOT_SUPPORT_ERROR = "TAG [earliest | pending] not supported";
    private static final String QUANTITY_NOT_SUPPORT_ERROR = "QUANTITY not supported, just support TAG as latest";

    /**
     * thread pool of query section bloom store
     */
    private final NodeInfoService nodeInfoService;
    private final Wallet wallet;
    private final Manager manager;

    public TronJsonRpcImpl(NodeInfoService nodeInfoService, Wallet wallet, Manager manager) {
        super(wallet, manager);
        this.nodeInfoService = nodeInfoService;
        this.wallet = wallet;
        this.manager = manager;
    }

    @Override
    public String web3ClientVersion() {
        logger.info("[sniper] web3ClientVersion ...");
        Pattern shortVersion = Pattern.compile("(\\d\\.\\d).*");
        Matcher matcher = shortVersion.matcher(System.getProperty("java.version"));
        matcher.matches();

        return String.join("/", Arrays.asList(
                "TRON", "v" + Version.getVersion(),
                System.getProperty("os.name"),
                "Java" + matcher.group(1),
                Version.VERSION_NAME));
    }

    @Override
    public String web3Sha3(String data) throws JsonRpcInvalidParamsException {
        logger.info("[sniper] web3Sha3 => {}", data);
        byte[] input;
        try {
            input = ByteArray.fromHexString(data);
        } catch (Exception e) {
            throw new JsonRpcInvalidParamsException("invalid input value");
        }

        byte[] result = Hash.sha3(input);
        return ByteArray.toJsonHex(result);
    }

    @Override
    public String ethGetBlockTransactionCountByHash(String blockHash) throws JsonRpcInvalidParamsException {
        logger.info("[sniper] ethGetBlockTransactionCountByHash => {}", blockHash);
        Block b = getBlockByJsonHash(blockHash);
        if (b == null) {
            return null;
        }

        long n = b.getTransactionsList().size();
        return ByteArray.toJsonHex(n);
    }

    @Override
    public String ethGetBlockTransactionCountByNumber(String blockNumOrTag) throws JsonRpcInvalidParamsException {
        logger.info("[sniper] ethGetBlockTransactionCountByNumber => {}", blockNumOrTag);
        List<Transaction> list = wallet.getTransactionsByJsonBlockId(blockNumOrTag);
        if (list == null) {
            return null;
        }

        long n = list.size();
        return ByteArray.toJsonHex(n);
    }

    @Override
    public BlockResult ethGetBlockByHash(String blockHash, Boolean fullTransactionObjects) throws JsonRpcInvalidParamsException {
        logger.info("[sniper] ethGetBlockByHash => {} - {}", blockHash, fullTransactionObjects);
        final Block b = getBlockByJsonHash(blockHash);
        return getBlockResult(b, fullTransactionObjects);
    }

    @Override
    public BlockResult ethGetBlockByNumber(String blockNumOrTag, Boolean fullTransactionObjects) throws JsonRpcInvalidParamsException {
        final Block b = wallet.getByJsonBlockId(blockNumOrTag);
        BlockResult result = (b == null ? null : getBlockResult(b, fullTransactionObjects));
        logger.info("[sniper] ethGetBlockByNumber => {} - {} \n result: {}", blockNumOrTag, fullTransactionObjects, result);
        return result;
    }

    @Override
    public String getNetVersion() {
        logger.info("[sniper] getNetVersion ...");
        int chainId = Numeric.toBigInt(new byte[]{Wallet.getAddressPreFixByte()}).intValue();
        return Integer.toString(chainId);
    }


    @Override
    public String ethChainId() throws JsonRpcInternalException {
        logger.info("[sniper] ethChainId...");

        // return hash of genesis block
        try {
//      byte[] chainId = wallet.getBlockCapsuleByNum(0).getBlockId().getBytes();
//      return ByteArray.toJsonHex(Arrays.copyOfRange(chainId, chainId.length - 4, chainId.length));

            String chainId = Wallet.getAddressPreFixString();
            return ByteArray.toJsonHex(chainId);
        } catch (Exception e) {
            throw new JsonRpcInternalException(e.getMessage());
        }
    }


    @Override
    public boolean isListening() {
        logger.info("[sniper] isListening...");
        int activeConnectCount = nodeInfoService.getNodeInfo().getActiveConnectCount();
        return activeConnectCount >= 1;
    }

    @Override
    public String getProtocolVersion() {
        logger.info("[sniper] getProtocolVersion...");
        return ByteArray.toJsonHex(wallet.getNowBlock().getBlockHeader().getRawData().getVersion());
    }

    @Override
    public String getLatestBlockNum() {
        String block = ByteArray.toJsonHex(wallet.getNowBlock().getBlockHeader().getRawData().getNumber());
        logger.info("[sniper] getLatestBlockNum {}", block);
        return block;
    }

    @Override
    public String getTrxBalance(String address, String blockNumOrTag) throws JsonRpcInvalidParamsException {
        logger.info("[sniper] eth_getBalance ==> {} - {}", address, blockNumOrTag);
        //@TODO not use blockNumOrTag
        byte[] addressData = addressCompatibleToByteArray(address);

        Account account = Account.newBuilder().setAddress(ByteString.copyFrom(addressData)).build();
        Account reply = wallet.getAccount(account);
        long balance = 0;

        if (reply != null) {
            balance = reply.getBalance();
        }
        return ByteArray.toJsonHex(balance);

//        if (EARLIEST_STR.equalsIgnoreCase(blockNumOrTag) || PENDING_STR.equalsIgnoreCase(blockNumOrTag)) {
//            throw new JsonRpcInvalidParamsException(TAG_NOT_SUPPORT_ERROR);
//        } else if (LATEST_STR.equalsIgnoreCase(blockNumOrTag)) {
//            byte[] addressData = addressCompatibleToByteArray(address);
//
//            Account account = Account.newBuilder().setAddress(ByteString.copyFrom(addressData)).build();
//            Account reply = wallet.getAccount(account);
//            long balance = 0;
//
//            if (reply != null) {
//                balance = reply.getBalance();
//            }
//            return ByteArray.toJsonHex(balance);
//        } else {
//            try {
//                ByteArray.hexToBigInteger(blockNumOrTag);
//            } catch (Exception e) {
//                throw new JsonRpcInvalidParamsException(BLOCK_NUM_ERROR);
//            }
//
//            throw new JsonRpcInvalidParamsException(QUANTITY_NOT_SUPPORT_ERROR);
//        }
    }


    @Override
    public String getStorageAt(String address, String storageIdx, String blockNumOrTag) throws JsonRpcInvalidParamsException {
        logger.info("[sniper] getStorageAt...");

        if (EARLIEST_STR.equalsIgnoreCase(blockNumOrTag)
                || PENDING_STR.equalsIgnoreCase(blockNumOrTag)) {
            throw new JsonRpcInvalidParamsException(TAG_NOT_SUPPORT_ERROR);
        } else if (LATEST_STR.equalsIgnoreCase(blockNumOrTag)) {
            byte[] addressByte = addressCompatibleToByteArray(address);

            // get contract from contractStore
            BytesMessage.Builder build = BytesMessage.newBuilder();
            BytesMessage bytesMessage = build.setValue(ByteString.copyFrom(addressByte)).build();
            SmartContract smartContract = wallet.getContract(bytesMessage);
            if (smartContract == null) {
                return ByteArray.toJsonHex(new byte[32]);
            }

            StorageRowStore store = manager.getStorageRowStore();
            Storage storage = new Storage(addressByte, store);
            storage.setContractVersion(smartContract.getVersion());

            DataWord value = storage.getValue(new DataWord(ByteArray.fromHexString(storageIdx)));
            return ByteArray.toJsonHex(value == null ? new byte[32] : value.getData());
        } else {
            try {
                ByteArray.hexToBigInteger(blockNumOrTag);
            } catch (Exception e) {
                throw new JsonRpcInvalidParamsException(BLOCK_NUM_ERROR);
            }

            throw new JsonRpcInvalidParamsException(QUANTITY_NOT_SUPPORT_ERROR);
        }
    }

    @Override
    public String getABIOfSmartContract(String contractAddress, String blockNumOrTag) throws JsonRpcInvalidParamsException {
        logger.info("[sniper] getABIOfSmartContract ==> {}, {}", contractAddress, contractAddress);

        if (EARLIEST_STR.equalsIgnoreCase(blockNumOrTag)
                || PENDING_STR.equalsIgnoreCase(blockNumOrTag)) {
            throw new JsonRpcInvalidParamsException(TAG_NOT_SUPPORT_ERROR);
        } else if (LATEST_STR.equalsIgnoreCase(blockNumOrTag)) {
            byte[] addressData = addressCompatibleToByteArray(contractAddress);

            BytesMessage.Builder build = BytesMessage.newBuilder();
            BytesMessage bytesMessage = build.setValue(ByteString.copyFrom(addressData)).build();
            SmartContractDataWrapper contractDataWrapper = wallet.getContractInfo(bytesMessage);

            if (contractDataWrapper != null) {
                return ByteArray.toJsonHex(contractDataWrapper.getRuntimecode().toByteArray());
            } else {
                return "0x";
            }

        } else {
            try {
                ByteArray.hexToBigInteger(blockNumOrTag);
            } catch (Exception e) {
                throw new JsonRpcInvalidParamsException(BLOCK_NUM_ERROR);
            }

            throw new JsonRpcInvalidParamsException(QUANTITY_NOT_SUPPORT_ERROR);
        }
    }

    @Override
    public String getCoinbase() throws JsonRpcInternalException {
        logger.info("[sniper] getCoinbase ...");

        String address = wallet.getCoinbase();

        if (StringUtils.isEmpty(address)) {
            throw new JsonRpcInternalException("etherbase must be explicitly specified");
        }

        return address;
    }

    @Override
    public String gasPrice() {
        logger.info("[sniper] gasPrice...");
        return ByteArray.toJsonHex(wallet.getEnergyFee());
    }

    @Override
    public String estimateGas(CallArguments args) throws JsonRpcInvalidRequestException, JsonRpcInvalidParamsException, JsonRpcInternalException {
        logger.info("[sniper] estimateGas ===> {}", args);

        byte[] ownerAddress = addressCompatibleToByteArray(args.getFrom());

        ContractType contractType = args.getContractType(wallet);
        if (contractType == ContractType.TransferContract) {
            buildTransferContractTransaction(ownerAddress, new BuildArguments(args));
            return "0x0";
        }

        TransactionExtention.Builder trxExtBuilder = TransactionExtention.newBuilder();
        Return.Builder retBuilder = Return.newBuilder();

        try {
            byte[] contractAddress;

            if (contractType == ContractType.TriggerSmartContract) {
                contractAddress = addressCompatibleToByteArray(args.getTo());
            } else {
                contractAddress = new byte[0];
            }

            callTriggerConstantContract(ownerAddress,
                    contractAddress,
                    args.parseValue(),
                    ByteArray.fromHexString(args.getData()),
                    trxExtBuilder,
                    retBuilder);

            return ByteArray.toJsonHex(trxExtBuilder.getEnergyUsed());
        } catch (ContractValidateException e) {
            String errString = "invalid contract";
            if (e.getMessage() != null) {
                errString = e.getMessage();
            }

            throw new JsonRpcInvalidRequestException(errString);
        } catch (Exception e) {
            String errString = JSON_ERROR;
            if (e.getMessage() != null) {
                errString = e.getMessage().replaceAll("[\"]", "'");
            }

            throw new JsonRpcInternalException(errString);
        }
    }

    @Override
    public TransactionResult getTransactionByHash(String txId) throws JsonRpcInvalidParamsException {
        logger.info("[sniper] getTransactionByHash ===> {}", txId);

        ByteString transactionId = ByteString.copyFrom(hashToByteArray(txId));

        TransactionInfo transactionInfo = wallet.getTransactionInfoById(transactionId);
        if (transactionInfo == null) {
            TransactionCapsule transactionCapsule = wallet.getTransactionCapsuleById(transactionId);
            if (transactionCapsule == null) {
                return null;
            }

            BlockCapsule blockCapsule = wallet.getBlockCapsuleByNum(transactionCapsule.getBlockNum());
            if (blockCapsule == null) {
                return new TransactionResult(transactionCapsule.getInstance(), wallet);
            } else {
                int transactionIndex = getTransactionIndex(
                        ByteArray.toHexString(transactionCapsule.getTransactionId().getBytes()),
                        blockCapsule.getInstance().getTransactionsList());

                if (transactionIndex == -1) {
                    return null;
                }

                long energyUsageTotal = 0;
                return new TransactionResult(blockCapsule, transactionIndex,
                        transactionCapsule.getInstance(), energyUsageTotal,
                        wallet.getEnergyFee(blockCapsule.getTimeStamp()), wallet);
            }
        } else {
            Block block = wallet.getBlockByNum(transactionInfo.getBlockNumber());
            if (block == null) {
                return null;
            }

            return formatTransactionResult(transactionInfo, block);
        }
    }

    @Override
    public TransactionResult getTransactionByBlockHashAndIndex(String blockHash, String index)
            throws JsonRpcInvalidParamsException {
        final Block block = getBlockByJsonHash(blockHash);

        if (block == null) {
            return null;
        }

        return getTransactionByBlockAndIndex(block, index);
    }

    @Override
    public TransactionResult getTransactionByBlockNumberAndIndex(String blockNumOrTag, String index) throws JsonRpcInvalidParamsException {
        logger.info("[sniper] getTransactionReceipt ===> {}, {}", blockNumOrTag, index);
        Block block = wallet.getByJsonBlockId(blockNumOrTag);
        if (block == null) {
            return null;
        }

        return getTransactionByBlockAndIndex(block, index);
    }

    @Override
    public TransactionReceipt getTransactionReceipt(String txId) throws JsonRpcInvalidParamsException {
        logger.info("[sniper] getTransactionReceipt ===> {}", txId);
        TransactionInfo transactionInfo = wallet.getTransactionInfoById(ByteString.copyFrom(hashToByteArray(txId)));
        if (transactionInfo == null) {
            return null;
        }

        Block block = wallet.getBlockByNum(transactionInfo.getBlockNumber());
        if (block == null) {
            return null;
        }

        return new TransactionReceipt(block, transactionInfo, wallet);
    }

    @Override
    public String getCall(CallArguments transactionCall, String blockNumOrTag) throws JsonRpcInvalidParamsException {
        logger.info("[sniper] getCall ===> {}-{}", transactionCall, blockNumOrTag);

        if (EARLIEST_STR.equalsIgnoreCase(blockNumOrTag)
                || PENDING_STR.equalsIgnoreCase(blockNumOrTag)) {
            throw new JsonRpcInvalidParamsException(TAG_NOT_SUPPORT_ERROR);
        } else if (LATEST_STR.equalsIgnoreCase(blockNumOrTag)) {
            byte[] addressData = addressCompatibleToByteArray(transactionCall.getFrom());
            byte[] contractAddressData = addressCompatibleToByteArray(transactionCall.getTo());

            return call(addressData, contractAddressData, transactionCall.parseValue(), ByteArray.fromHexString(transactionCall.getData()));
        } else {
            try {
                ByteArray.hexToBigInteger(blockNumOrTag).longValue();
            } catch (Exception e) {
                throw new JsonRpcInvalidParamsException(BLOCK_NUM_ERROR);
            }

            throw new JsonRpcInvalidParamsException(QUANTITY_NOT_SUPPORT_ERROR);
        }
    }

    @Override
    public String getPeerCount() {
        logger.info("[sniper] getPeerCount ");

        // return the peer list count
        return ByteArray.toJsonHex(nodeInfoService.getNodeInfo().getPeerList().size());
    }

    @Override
    public Object getSyncingStatus() {
        logger.info("[sniper] getSyncingStatus ");

        if (nodeInfoService.getNodeInfo().getPeerList().isEmpty()) {
            return false;
        }

        long startingBlockNum = nodeInfoService.getNodeInfo().getBeginSyncNum();
        Block nowBlock = wallet.getNowBlock();
        long currentBlockNum = nowBlock.getBlockHeader().getRawData().getNumber();
        long diff = (System.currentTimeMillis() - nowBlock.getBlockHeader().getRawData().getTimestamp()) / 3000;
        diff = diff > 0 ? diff : 0;
        long highestBlockNum = currentBlockNum + diff; // estimated the highest block number

        return new SyncingResult(ByteArray.toJsonHex(startingBlockNum),
                ByteArray.toJsonHex(currentBlockNum),
                ByteArray.toJsonHex(highestBlockNum)
        );
    }

    @Override
    public BlockResult getUncleByBlockHashAndIndex(String blockHash, String index) {
        logger.info("[sniper] getUncleByBlockHashAndIndex ");
        return null;
    }

    @Override
    public BlockResult getUncleByBlockNumberAndIndex(String blockNumOrTag, String index) {
        logger.info("[sniper] getUncleByBlockNumberAndIndex ");
        return null;
    }

    @Override
    public String getUncleCountByBlockHash(String blockHash) {
        logger.info("[sniper] getUncleByBlockNumberAndIndex ");
        return "0x0";
    }

    @Override
    public String getUncleCountByBlockNumber(String blockNumOrTag) {
        return "0x0";
    }

    @Override
    public List<Object> ethGetWork() {
        Block block = wallet.getNowBlock();
        String blockHash = null;

        if (block != null) {
            blockHash = ByteArray.toJsonHex(new BlockCapsule(block).getBlockId().getBytes());
        }

        return Arrays.asList(
                blockHash,
                null,
                null
        );
    }

    @Override
    public String getHashRate() {
        return "0x0";
    }

    @Override
    public boolean isMining() {
        return wallet.isMining();
    }

    @Override
    public String[] getAccounts() {
        return new String[0];
    }


    @Override
    public TransactionJson buildTransaction(BuildArguments args) throws JsonRpcInvalidParamsException, JsonRpcInvalidRequestException, JsonRpcInternalException, JsonRpcMethodNotFoundException {
        logger.info("[sniper] buildTransaction ==> {} ", args);

        if (getSource() != RequestSource.FULLNODE) {
            String msg = String.format("the method buildTransaction does not exist/is not available in %s", getSource().toString());
            throw new JsonRpcMethodNotFoundException(msg);
        }

        byte[] fromAddressData;
        try {
            fromAddressData = addressCompatibleToByteArray(args.getFrom());
        } catch (JsonRpcInvalidParamsException e) {
            throw new JsonRpcInvalidRequestException(JSON_ERROR);
        }

        // check possible ContractType
        ContractType contractType = args.getContractType(wallet);
        switch (contractType.getNumber()) {
            case ContractType.CreateSmartContract_VALUE:
                return buildCreateSmartContractTransaction(fromAddressData, args);
            case ContractType.TriggerSmartContract_VALUE:
                return buildTriggerSmartContractTransaction(fromAddressData, args);
            case ContractType.TransferContract_VALUE:
                return buildTransferContractTransaction(fromAddressData, args);
            case ContractType.TransferAssetContract_VALUE:
                return buildTransferAssetContractTransaction(fromAddressData, args);
            default:
                break;
        }

        return null;
    }

    @Override
    public boolean ethSubmitWork(String nonceHex, String headerHex, String digestHex) throws JsonRpcMethodNotFoundException {
        logger.info("[sniper] ethSendTransaction => nonceHex{} headerHex {} digestHex {}", nonceHex, headerHex, digestHex);
        throw new JsonRpcMethodNotFoundException("the method eth_submitWork does not exist/is not available");
    }


    @Override
    public String ethSendRawTransaction(String rawData) throws JsonRpcMethodNotFoundException {
        logger.info("[sniper] ethSendRawTransaction => {}", rawData);
        try {
            SignedRawTransaction rawTransaction = (SignedRawTransaction) TransactionDecoder.decode(rawData);
            String ownerAddress = recoverAddress(rawTransaction, Wallet.getAddressPreFixByte());
            rawTransaction.verify(ownerAddress);

            BuildArguments args = new BuildArguments();
            args.setFrom(rawTransaction.getFrom());
            args.setGas(Numeric.toHexStringWithPrefix(rawTransaction.getGasLimit()));
            args.setGasPrice(Numeric.toHexStringWithPrefix(rawTransaction.getGasPrice()));
            args.setNonce(Numeric.toHexStringWithPrefix(rawTransaction.getNonce()));
            args.setData(rawTransaction.getData());
            args.setValue(Numeric.toHexStringWithPrefix(rawTransaction.getValue()));
            args.setTo(rawTransaction.getTo());
            args.setName("No name");
            args.setConsumeUserResourcePercent(10L);//@TODO set default
            args.setOriginEnergyLimit(1_000_000L);//@TODO set default


            TransactionJson txJson = buildTransaction(args);
            Transaction tx = Util.packTransaction(txJson.getTransaction().toJSONString(), false);

            logger.info("Data in raw =====> {}", Numeric.toHexString(tx.getRawData().getData().toByteArray()));
            logger.info("Value Contract =====> {}", Numeric.toHexString(tx.getRawData().getContract(0).getParameter().getValue().toByteArray()));

            Objects.requireNonNull(tx, "Tx null");
            logger.info("eth_sendRawTransaction tx: {}", tx);
            wallet.broadcastTransaction(tx, true);

            return "0x" + org.tron.core.utils.TransactionUtil.getTransactionId(tx);
        } catch (Exception e) {
            logger.error("eth_sendRawTransaction error: ", e);
            throw new JsonRpcMethodNotFoundException(e.getMessage());
        }
    }

    @Override
    public String ethSendTransaction(CallArguments args) throws JsonRpcMethodNotFoundException {
        logger.info("[sniper] ethSendTransaction => {}", args);
        throw new JsonRpcMethodNotFoundException("the method eth_sendTransaction does not exist/is not available");
    }

    @Override
    public String ethSign(String address, String msg) throws JsonRpcMethodNotFoundException {
        logger.info("[sniper] ethSign => address {} msg {}", address, msg);
        throw new JsonRpcMethodNotFoundException("the method eth_sign does not exist/is not available");
    }

    @Override
    public String ethSignTransaction(CallArguments transactionArgs) throws JsonRpcMethodNotFoundException {
        logger.info("[sniper] ethSignTransaction => CallArguments {}", transactionArgs);
        throw new JsonRpcMethodNotFoundException("the method eth_signTransaction does not exist/is not available");
    }

    @Override
    public String parityNextNonce(String address) throws JsonRpcMethodNotFoundException {
        logger.info("[sniper] parityNextNonce ==> {} ", address);
        throw new JsonRpcMethodNotFoundException("the method parity_nextNonce does not exist/is not available");
    }

    //@TODO
    @Override
    public String getSendTransactionCountOfAddress(String address, String blockNumOrTag) throws JsonRpcMethodNotFoundException, JsonRpcInvalidParamsException {
        byte[] addressByte = addressCompatibleToByteArray(address);
        List<Transaction> transactions = wallet.getTransactionsByJsonBlockId(blockNumOrTag);
        Long count = transactions.stream()
                .map(Transaction::getRawData)
                .map(Transaction.raw::getContractList)
                .flatMap(Collection::stream)
                .map(TransactionUtil::getOwner)
                .filter(owner -> Arrays.equals(owner, addressByte))
                .count();
        logger.info("[sniper] getSendTransactionCountOfAddress ==> {}-{}-{}", address, blockNumOrTag, count);
        return ByteArray.toJsonHex(count);
    }

    @Override
    public String[] getCompilers() throws JsonRpcMethodNotFoundException {
        logger.info("[sniper] getCompilers... ");
        throw new JsonRpcMethodNotFoundException("the method eth_getCompilers does not exist/is not available");
    }

    @Override
    public CompilationResult ethCompileSolidity(String contract) throws JsonRpcMethodNotFoundException {
        logger.info("[sniper] ethCompileSolidity ==> {} ", contract);
        throw new JsonRpcMethodNotFoundException("the method eth_compileSolidity does not exist/is not available");
    }

    @Override
    public CompilationResult ethCompileLLL(String contract) throws JsonRpcMethodNotFoundException {
        logger.info("[sniper] ethCompileLLL ==> {} ", contract);
        throw new JsonRpcMethodNotFoundException("the method eth_compileLLL does not exist/is not available");
    }

    @Override
    public CompilationResult ethCompileSerpent(String contract) throws JsonRpcMethodNotFoundException {
        logger.info("[sniper] ethCompileSerpent ==> {} ", contract);
        throw new JsonRpcMethodNotFoundException("the method eth_compileSerpent does not exist/is not available");
    }

    @Override
    public CompilationResult ethSubmitHashrate(String hashrate, String id) throws JsonRpcMethodNotFoundException {
        logger.info("[sniper] ethSubmitHashrate ==> {} ", hashrate);
        throw new JsonRpcMethodNotFoundException("the method eth_submit Hashrate does not exist/is not available");
    }

    @Override
    public String newFilter(FilterRequest fr) throws JsonRpcInvalidParamsException, JsonRpcMethodNotFoundException {
        logger.info("[sniper] newFilter ==> {} ", fr);

        disableInPBFT("eth_newFilter");

        Map<String, LogFilterAndResult> eventFilter2Result;
        if (getSource() == RequestSource.FULLNODE) {
            eventFilter2Result = eventFilter2ResultFull;
        } else {
            eventFilter2Result = eventFilter2ResultSolidity;
        }

        long currentMaxFullNum = wallet.getNowBlock().getBlockHeader().getRawData().getNumber();
        LogFilterAndResult logFilterAndResult = new LogFilterAndResult(fr, currentMaxFullNum, wallet);
        String filterID = generateFilterId();
        eventFilter2Result.put(filterID, logFilterAndResult);
        return ByteArray.toJsonHex(filterID);
    }

    @Override
    public String newBlockFilter() throws JsonRpcMethodNotFoundException {
        logger.info("[sniper] newBlockFilter...");

        disableInPBFT("eth_newBlockFilter");

        Map<String, BlockFilterAndResult> blockFilter2Result;
        if (getSource() == RequestSource.FULLNODE) {
            blockFilter2Result = blockFilter2ResultFull;
        } else {
            blockFilter2Result = blockFilter2ResultSolidity;
        }

        BlockFilterAndResult filterAndResult = new BlockFilterAndResult();
        String filterID = generateFilterId();
        blockFilter2Result.put(filterID, filterAndResult);
        return ByteArray.toJsonHex(filterID);
    }

    @Override
    public boolean uninstallFilter(String filterId) throws ItemNotFoundException, JsonRpcMethodNotFoundException {
        disableInPBFT("eth_uninstallFilter");

        Map<String, BlockFilterAndResult> blockFilter2Result;
        Map<String, LogFilterAndResult> eventFilter2Result;
        if (getSource() == RequestSource.FULLNODE) {
            blockFilter2Result = blockFilter2ResultFull;
            eventFilter2Result = eventFilter2ResultFull;
        } else {
            blockFilter2Result = blockFilter2ResultSolidity;
            eventFilter2Result = eventFilter2ResultSolidity;
        }

        filterId = ByteArray.fromHex(filterId);
        if (eventFilter2Result.containsKey(filterId)) {
            eventFilter2Result.remove(filterId);
        } else if (blockFilter2Result.containsKey(filterId)) {
            blockFilter2Result.remove(filterId);
        } else {
            throw new ItemNotFoundException(FILTER_NOT_FOUND);
        }

        return true;
    }

    @Override
    public Object[] getFilterChanges(String filterId) throws ItemNotFoundException,
            JsonRpcMethodNotFoundException {
        disableInPBFT("eth_getFilterChanges");

        Map<String, BlockFilterAndResult> blockFilter2Result;
        Map<String, LogFilterAndResult> eventFilter2Result;
        if (getSource() == RequestSource.FULLNODE) {
            blockFilter2Result = blockFilter2ResultFull;
            eventFilter2Result = eventFilter2ResultFull;
        } else {
            blockFilter2Result = blockFilter2ResultSolidity;
            eventFilter2Result = eventFilter2ResultSolidity;
        }

        filterId = ByteArray.fromHex(filterId);

        return getFilterResult(filterId, blockFilter2Result, eventFilter2Result);
    }

    @Override
    public LogFilterElement[] getLogs(FilterRequest fr) throws JsonRpcInvalidParamsException,
            ExecutionException, InterruptedException, BadItemException, ItemNotFoundException,
            JsonRpcMethodNotFoundException, JsonRpcTooManyResultException {
        disableInPBFT("eth_getLogs");

        long currentMaxBlockNum = wallet.getNowBlock().getBlockHeader().getRawData().getNumber();
        //convert FilterRequest to LogFilterWrapper
        LogFilterWrapper logFilterWrapper = new LogFilterWrapper(fr, currentMaxBlockNum, wallet);

        return getLogsByLogFilterWrapper(logFilterWrapper, currentMaxBlockNum);
    }

    @Override
    public LogFilterElement[] getFilterLogs(String filterId) throws ExecutionException,
            InterruptedException, BadItemException, ItemNotFoundException,
            JsonRpcMethodNotFoundException, JsonRpcTooManyResultException {
        disableInPBFT("eth_getFilterLogs");

        Map<String, LogFilterAndResult> eventFilter2Result;
        if (getSource() == RequestSource.FULLNODE) {
            eventFilter2Result = eventFilter2ResultFull;
        } else {
            eventFilter2Result = eventFilter2ResultSolidity;
        }

        filterId = ByteArray.fromHex(filterId);
        if (!eventFilter2Result.containsKey(filterId)) {
            throw new ItemNotFoundException(FILTER_NOT_FOUND);
        }

        LogFilterWrapper logFilterWrapper = eventFilter2Result.get(filterId).getLogFilterWrapper();
        long currentMaxBlockNum = wallet.getNowBlock().getBlockHeader().getRawData().getNumber();

        return getLogsByLogFilterWrapper(logFilterWrapper, currentMaxBlockNum);
    }

}
