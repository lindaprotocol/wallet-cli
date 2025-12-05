package org.linda.demo;

import static org.linda.common.utils.Utils.failedHighlight;

import com.google.protobuf.ByteString;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;
import org.junit.Assert;
import org.linda.api.GrpcAPI;
import org.linda.api.GrpcAPI.Note;
import org.linda.api.GrpcAPI.PrivateShieldedLRC20Parameters;
import org.linda.api.GrpcAPI.ReceiveNote;
import org.linda.api.GrpcAPI.Return;
import org.linda.api.GrpcAPI.SpendNoteLRC20;
import org.linda.api.GrpcAPI.TransactionExtention;
import org.linda.common.crypto.ECKey;
import org.linda.common.crypto.Sha256Sm3Hash;
import org.linda.common.utils.AbiUtil;
import org.linda.common.utils.ByteArray;
import org.linda.common.utils.ByteUtil;
import org.linda.common.utils.Hash;
import org.linda.common.utils.TransactionUtils;
import org.linda.common.zksnark.JLibrustzcash;
import org.linda.core.config.Parameter.CommonConstant;
import org.linda.core.exception.ZksnarkException;
import org.linda.core.zen.address.DiversifierT;
import org.linda.core.zen.address.ExpandedSpendingKey;
import org.linda.core.zen.address.IncomingViewingKey;
import org.linda.core.zen.address.KeyIo;
import org.linda.core.zen.address.SpendingKey;
import org.linda.protos.Protocol;
import org.linda.protos.Protocol.Transaction;
import org.linda.protos.Protocol.Transaction.Result;
import org.linda.protos.Protocol.TransactionInfo;
import org.linda.protos.contract.SmartContractOuterClass;
import org.linda.protos.contract.SmartContractOuterClass.TriggerSmartContract;
import org.linda.walletserver.GrpcClient;
import org.linda.walletserver.WalletApi;


@Slf4j
public class ShieldedLRC20Demo {

  private byte[] lrc20 = WalletApi.decodeFromBase58Check(
      "LaN7YzfkFM5NYqLfQzu7pghm7K4xezNxiS");
  private byte[] shieldedLRC20 = WalletApi.decodeFromBase58Check(
      "LZVehPRXfwPzksbHfhV2tSPZRUttYR8KYf");

  private String privateKey = "your private key of transparent address";

  private String sk = "your sk of shielded address";
  private String rcm = "should generate new rcm when trigger contract";
  private ShieldedKey shieldedKey = generateShieldedKey(sk);

  private GrpcClient grpcClient = WalletApi.init();
  private BigInteger scalingFactorBi = getScalingFactorBi();

  public ShieldedLRC20Demo() throws ZksnarkException {
  }

  public static void main(String[] args) throws ZksnarkException, InterruptedException {
    ShieldedLRC20Demo demo = new ShieldedLRC20Demo();
    demo.mintDemo(demo.privateKey, 1, demo.shieldedKey.getKioAddress());
    demo.transferDemo(demo.privateKey, 5, demo.shieldedKey.getKioAddress(),
        2, 3);
    demo.burnDemo(demo.privateKey, 5, demo.shieldedKey.getKioAddress(), 3,
        getAddressFromPk(demo.privateKey), 2);
  }

  public static byte[] getAddressFromPk(String pk) {
    ECKey ecKey = ECKey.fromPrivate(ByteArray.fromHexString(pk));
    return ecKey.getAddress();
  }

  public static byte[] generateSk() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return bytes;
  }

  public static byte[] generateD() throws ZksnarkException {
    byte[] bytes = new byte[11];
    while(true) {
      new SecureRandom().nextBytes(bytes);
      if (JLibrustzcash.librustzcashCheckDiversifier(bytes)) {
        return bytes;
      }
    }
  }

  public static String generateRcm() {
    return ByteArray.toHexString(WalletApi.getRcm().get().getValue().toByteArray());
  }

  public void testGenerateKeys() throws ZksnarkException {
    ShieldedKey shieldedKey = generateShieldedKey(sk);
    System.out.println("ask=" + shieldedKey.getHexAsk());
    System.out.println("nsk=" + shieldedKey.getHexNsk());
    System.out.println("ovk=" + shieldedKey.getHexOvk());
    System.out.println("ak=" + shieldedKey.getHexAk());
    System.out.println("nk=" + shieldedKey.getHexNk());
    System.out.println("ivk=" + shieldedKey.getHexIvk());
    System.out.println("address=" + shieldedKey.getKioAddress());
  }

  public ShieldedKey generateShieldedKey(String sKey) throws ZksnarkException {
    byte[] sk;
    if (sKey.equalsIgnoreCase("")) {
      sk = generateSk();
    } else {
      sk = ByteArray.fromHexString(sKey);
    }
    byte[] d = generateD();
    ShieldedKey key = new ShieldedKey();
    SpendingKey spendingKey = new SpendingKey(sk);
    ExpandedSpendingKey esk = spendingKey.expandedSpendingKey();
    key.setSk(sk);
    key.setAsk(esk.getAsk());
    key.setNsk(esk.getNsk());
    key.setOvk(esk.getOvk());
    key.setAk(ExpandedSpendingKey.getAkFromAsk(esk.getAsk()));
    key.setNk(ExpandedSpendingKey.getNkFromNsk(esk.getNsk()));
    key.setIvk(spendingKey.fullViewingKey().inViewingKey().getValue());
    key.setD(d);
    return key;
  }

  public void addReceiveShieldedNote(PrivateShieldedLRC20Parameters.Builder paramBuilder,
      String receiveShieldedAddress, long value) {
    byte[] memo = new byte[512];
    Note note = buildNote(value, receiveShieldedAddress, ByteArray.fromHexString(rcm), memo);
    ReceiveNote.Builder receiveNote = GrpcAPI.ReceiveNote.newBuilder();
    receiveNote.setNote(note);
    paramBuilder.addShieldedReceives(receiveNote);
  }

  public void setTransparent(PrivateShieldedLRC20Parameters.Builder paramBuilder,
      long fromAmount, byte[] toTransparentAddress, long toTransparentAmount) {
    paramBuilder.setFromAmount(getScaledPublicAmount(fromAmount));
    paramBuilder.setToAmount(toTransparentAmount + "");
    if (toTransparentAddress != null) {
      paramBuilder.setTransparentToAddress(ByteString.copyFrom(toTransparentAddress));
    }
  }

  public void setContractAddress(PrivateShieldedLRC20Parameters.Builder paramBuilder) {
    paramBuilder.setShieldedLRC20ContractAddress(ByteString.copyFrom(shieldedLRC20));
  }

  public void setKey(PrivateShieldedLRC20Parameters.Builder paramBuilder, byte[] ask, byte[] nsk,
      byte[] ovk) {
    if (ask != null) {
      paramBuilder.setAsk(ByteString.copyFrom(ask));
    }
    if (nsk != null) {
      paramBuilder.setNsk(ByteString.copyFrom(nsk));
    }
    if (ovk != null) {
      paramBuilder.setOvk(ByteString.copyFrom(ovk));
    }
  }

  public String mintDemo(String fromPrivate, long fromAmount, String toShieldedAddress)
      throws InterruptedException {
    setAllowance(fromPrivate, fromAmount);
    Thread.sleep(2000);
    PrivateShieldedLRC20Parameters.Builder paramBuilder =
        GrpcAPI.PrivateShieldedLRC20Parameters.newBuilder();
    //set receive note
    addReceiveShieldedNote(paramBuilder, toShieldedAddress, fromAmount);
    //set transparent
    setTransparent(paramBuilder, fromAmount, null, 0);
    //set key
    setKey(paramBuilder, null, null, shieldedKey.getOvk());
    //set contract address
    setContractAddress(paramBuilder);
    GrpcAPI.ShieldedLRC20Parameters lrc20MintParams =
        WalletApi.createShieldedContractParameters(paramBuilder.build());
    return triggerMint(fromPrivate, lrc20MintParams.getTriggerContractInput());
  }

  public void transferDemo(String fromPrivate, long fromAmount, String toShieldedAddress,
      long toAmount1, long toAmount2) throws InterruptedException {
    String hash = mintDemo(fromPrivate, fromAmount, toShieldedAddress);
    Optional<TransactionInfo> infoById = waitToGetTransactionInfo(hash);

    PrivateShieldedLRC20Parameters.Builder privateLRC20Builder =
        PrivateShieldedLRC20Parameters.newBuilder();
    //set spend note
    Note note = buildNote(5, toShieldedAddress, ByteArray.fromHexString(rcm), new byte[512]);
    privateLRC20Builder.addShieldedSpends(getSpendNote(infoById.get(), note, shieldedLRC20));
    //set receive note 1
    addReceiveShieldedNote(privateLRC20Builder, toShieldedAddress, toAmount1);
    //set receive note 2
    addReceiveShieldedNote(privateLRC20Builder, toShieldedAddress, toAmount2);
    //set contract address
    setContractAddress(privateLRC20Builder);
    //set key
    setKey(privateLRC20Builder, shieldedKey.getAsk(), shieldedKey.getNsk(), shieldedKey.getOvk());
    //no need to set transparent

    GrpcAPI.ShieldedLRC20Parameters transferParam = WalletApi
        .createShieldedContractParameters(privateLRC20Builder.build());
    triggerTransfer(shieldedLRC20, privateKey, transferParam.getTriggerContractInput());
  }

  public void burnDemo(String fromPrivate, long fromAmount, String toShieldedAddress,
      long toShieldedAmount,  byte[] toTransparentAddress, long toTransparentAmount)
      throws InterruptedException {
    String hash = mintDemo(fromPrivate, fromAmount, toShieldedAddress);
    Optional<TransactionInfo> infoById = waitToGetTransactionInfo(hash);
    Note note = buildNote(fromAmount, toShieldedAddress,
        ByteArray.fromHexString(rcm), new byte[512]);

    PrivateShieldedLRC20Parameters.Builder privateLRC20Builder =
        PrivateShieldedLRC20Parameters.newBuilder();
    //set key
    setKey(privateLRC20Builder, shieldedKey.getAsk(), shieldedKey.getNsk(), shieldedKey.getOvk());
    //set transparent
    setTransparent(privateLRC20Builder, 0, toTransparentAddress, toTransparentAmount);
    //set spend note
    privateLRC20Builder.addShieldedSpends(getSpendNote(infoById.get(), note, shieldedLRC20));
    //set receive note
    addReceiveShieldedNote(privateLRC20Builder, toShieldedAddress, toShieldedAmount);
    //set contract address
    setContractAddress(privateLRC20Builder);

    GrpcAPI.ShieldedLRC20Parameters burnParam = WalletApi
        .createShieldedContractParameters(privateLRC20Builder.build());

    triggerBurn(shieldedLRC20, privateKey, burnParam.getTriggerContractInput());
  }

  public GrpcAPI.Note buildNote(long value, String paymentAddress, byte[] rcm, byte[] memo) {
    GrpcAPI.Note.Builder noteBuilder = GrpcAPI.Note.newBuilder();
    noteBuilder.setValue(value);
    noteBuilder.setPaymentAddress(paymentAddress);
    noteBuilder.setRcm(ByteString.copyFrom(rcm));
    noteBuilder.setMemo(ByteString.copyFrom(memo));
    return noteBuilder.build();
  }

  public SpendNoteLRC20 getSpendNote(TransactionInfo txInfo, Note note, byte[] contractAddress) {
    byte[] txData = txInfo.getLog(1).getData().toByteArray();
    long pos = bytes32ToLong(ByteArray.subArray(txData, 0, 32));
    byte[] contractResult = triggerGetPath(contractAddress, pos);
    byte[] path = ByteArray.subArray(contractResult, 32, 1056);
    byte[] root = ByteArray.subArray(contractResult, 0, 32);
    GrpcAPI.SpendNoteLRC20.Builder noteBuilder = GrpcAPI.SpendNoteLRC20.newBuilder();
    noteBuilder.setAlpha(ByteString.copyFrom(WalletApi.getRcm().get().getValue().toByteArray()));
    noteBuilder.setPos(pos);
    noteBuilder.setPath(ByteString.copyFrom(path));
    noteBuilder.setRoot(ByteString.copyFrom(root));
    noteBuilder.setNote(note);
    return noteBuilder.build();
  }

  private String triggerMint(String privateKey, String input) {
    String methodSign = "mint(uint256,bytes32[9],bytes32[2],bytes32[21])";
    byte[] selector = new byte[4];
    System.arraycopy(Hash.sha3(methodSign.getBytes()), 0, selector, 0, 4);
    return triggerContract(shieldedLRC20,
        "mint(uint256,bytes32[9],bytes32[2],bytes32[21])",
        input,
        true,
        0L, 90000000L,
        "0", 0,
        privateKey);
  }

  private String triggerTransfer(byte[] contractAddress, String privateKey, String input) {
    String txid = triggerContract(contractAddress,
        "transfer(bytes32[10][],bytes32[2][],bytes32[9][],bytes32[2],bytes32[21][])",
        input,
        true,
        0L, 90000000L,
        "0",
        0,
        privateKey);
    WalletApi.getTransactionInfoById(txid);
    return txid;
  }

  private String triggerBurn(byte[] contractAddress, String privateKey, String input) {
    return triggerContract(contractAddress,
        "burn(bytes32[10],bytes32[2],uint256,bytes32[2],address,bytes32[3],bytes32[9][],"
            + "bytes32[21][])",
        input,
        true,
        0L, 100000000L,
        "0",
        0,
        privateKey);
  }


  private String triggerContract(byte[] contractAddress, String method, String argsStr,
      Boolean isHex, long callValue, long feeLimit, String tokenId, long tokenValue,
      String priKey) {
    WalletApi.setAddressPreFixByte(CommonConstant.ADD_PRE_FIX_BYTE_MAINNET);
    ECKey temKey = null;
    try {
      BigInteger priK = new BigInteger(priKey, 16);
      temKey = ECKey.fromPrivate(priK);
    } catch (Exception ex) {
      ex.printStackTrace();
    }
    final ECKey ecKey = temKey;
    if (argsStr.equalsIgnoreCase("#")) {
      logger.info("argsstr is #");
      argsStr = "";
    }

    byte[] owner = getAddressFromPk(priKey);
    byte[] input = Hex.decode(AbiUtil.parseMethod(method, argsStr, isHex));
    TriggerSmartContract.Builder builder = TriggerSmartContract.newBuilder();
    builder.setOwnerAddress(ByteString.copyFrom(owner));
    builder.setContractAddress(ByteString.copyFrom(contractAddress));
    builder.setData(ByteString.copyFrom(input));
    builder.setCallValue(callValue);
    builder.setTokenId(Long.parseLong(tokenId));
    builder.setCallTokenValue(tokenValue);
    TriggerSmartContract triggerContract = builder.build();

    TransactionExtention transactionExtention = grpcClient.triggerContract(triggerContract);
    if (transactionExtention == null || !transactionExtention.getResult().getResult()) {
      System.out.println("RPC create call lind " + failedHighlight() + "!");
      System.out.println("Code = " + transactionExtention.getResult().getCode());
      System.out
          .println("Message = " + transactionExtention.getResult().getMessage().toStringUtf8());
      return null;
    }
    Transaction transaction = transactionExtention.getTransaction();
    if (transaction.getRetCount() != 0
        && transactionExtention.getConstantResult(0) != null
        && transactionExtention.getResult() != null) {
      byte[] result = transactionExtention.getConstantResult(0).toByteArray();
      System.out.println("message:" + transaction.getRet(0).getRet());
      System.out.println(":" + ByteArray
          .toStr(transactionExtention.getResult().getMessage().toByteArray()));
      System.out.println("Result:" + Hex.toHexString(result));
      return null;
    }

    final TransactionExtention.Builder texBuilder = TransactionExtention.newBuilder();
    Transaction.Builder transBuilder = Transaction.newBuilder();
    Transaction.raw.Builder rawBuilder = transactionExtention.getTransaction().getRawData()
        .toBuilder();
    rawBuilder.setFeeLimit(feeLimit);

    transBuilder.setRawData(rawBuilder);
    for (int i = 0; i < transactionExtention.getTransaction().getSignatureCount(); i++) {
      ByteString s = transactionExtention.getTransaction().getSignature(i);
      transBuilder.setSignature(i, s);
    }
    for (int i = 0; i < transactionExtention.getTransaction().getRetCount(); i++) {
      Result r = transactionExtention.getTransaction().getRet(i);
      transBuilder.setRet(i, r);
    }
    texBuilder.setTransaction(transBuilder);
    texBuilder.setResult(transactionExtention.getResult());
    texBuilder.setTxid(transactionExtention.getTxid());

    transactionExtention = texBuilder.build();
    if (transactionExtention == null) {
      return null;
    }
    Return ret = transactionExtention.getResult();
    if (!ret.getResult()) {
      System.out.println("Code = " + ret.getCode());
      System.out.println("Message = " + ret.getMessage().toStringUtf8());
      return null;
    }
    transaction = transactionExtention.getTransaction();
    if (transaction == null || transaction.getRawData().getContractCount() == 0) {
      System.out.println("Transaction is empty");
      return null;
    }
    transaction = signTransaction(ecKey, transaction);
    String txid = ByteArray.toHexString(Sha256Sm3Hash.hash(
        transaction.getRawData().toByteArray()));
    System.out.println("trigger txid = " + txid);
    WalletApi.broadcastTransaction(transaction);
    return txid;
  }

  public static Protocol.Transaction signTransaction(ECKey ecKey, Transaction transaction) {
    WalletApi.setAddressPreFixByte(CommonConstant.ADD_PRE_FIX_BYTE_MAINNET);
    if (ecKey == null || ecKey.getPrivKey() == null) {
      //logger.warn("Warning: Can't sign,there is no private key !!");
      return null;
    }
    transaction = TransactionUtils.setTimestamp(transaction);
    logger.info("Txid in sign is " + ByteArray.toHexString(
        Sha256Sm3Hash.hash(transaction.getRawData().toByteArray())));
    return TransactionUtils.sign(transaction, ecKey);
  }

  private BigInteger getScalingFactorBi() {
    byte[] scalingFactorBytes = triggerGetScalingFactor(shieldedLRC20);
    return ByteUtil.bytesToBigInteger(scalingFactorBytes);
  }

  private byte[] triggerGetScalingFactor(byte[] contractAddress) {
    String methodSign = "scalingFactor()";
    byte[] selector = new byte[4];
    System.arraycopy(Hash.sha3(methodSign.getBytes()), 0, selector, 0, 4);
    SmartContractOuterClass.TriggerSmartContract.Builder triggerBuilder = SmartContractOuterClass
        .TriggerSmartContract.newBuilder();
    triggerBuilder.setContractAddress(ByteString.copyFrom(contractAddress));
    triggerBuilder.setData(ByteString.copyFrom(selector));
    GrpcAPI.TransactionExtention lindExt2 = grpcClient.triggerConstantContract(
        triggerBuilder.build());
    List<ByteString> list = lindExt2.getConstantResultList();
    byte[] result = new byte[0];
    for (ByteString bs : list) {
      result = ByteUtil.merge(result, bs.toByteArray());
    }
    Assert.assertEquals(32, result.length);
    return result;
  }

  public String getScaledPublicAmount(long amount) {
    BigInteger result = BigInteger.valueOf(amount).multiply(scalingFactorBi);
    return result.toString();
  }

  public void setAllowance(String privateKey, long amount) {
    byte[] shieldedContractAddressPadding = new byte[32];
    System.arraycopy(shieldedLRC20, 0,
        shieldedContractAddressPadding, 11, 21);
    byte[] valueBytes = longTo32Bytes(amount);
    String input = Hex.toHexString(ByteUtil.merge(shieldedContractAddressPadding, valueBytes));
    triggerContract(lrc20, "approve(address,uint256)", input, true,
        0L, 10000000L, "0", 0, privateKey);
  }

  public byte[] triggerGetPath(byte[] contractAddress, long pos) {
    String methodSign = "getPath(uint256)";
    byte[] selector = new byte[4];
    System.arraycopy(Hash.sha3(methodSign.getBytes()), 0, selector, 0, 4);
    SmartContractOuterClass.TriggerSmartContract.Builder triggerBuilder = SmartContractOuterClass
        .TriggerSmartContract.newBuilder();
    triggerBuilder.setContractAddress(ByteString.copyFrom(contractAddress));
    byte[] input = ByteUtil.merge(selector, longTo32Bytes(pos));
    triggerBuilder.setData(ByteString.copyFrom(input));

    GrpcAPI.TransactionExtention transactionExtention = grpcClient.triggerConstantContract(
        triggerBuilder.build());
    Assert.assertEquals(0, transactionExtention.getResult().getCodeValue());
    byte[] result = transactionExtention.getConstantResult(0).toByteArray();
    Assert.assertEquals(1056, result.length);
    return result;
  }

  private Optional<TransactionInfo> waitToGetTransactionInfo(String txid)
      throws InterruptedException {
    logger.info("mint txid: " + txid);
    Optional<TransactionInfo> infoById = WalletApi.getTransactionInfoById(txid);
    while (infoById.get().getLogList().size() < 2) {
      logger.info("Can not get transaction info, please wait....");
      Thread.sleep(5000);
      infoById = WalletApi.getTransactionInfoById(txid);
    }
    return infoById;
  }

  private static byte[] longTo32Bytes(long value) {
    byte[] longBytes = ByteArray.fromLong(value);
    byte[] zeroBytes = new byte[24];
    return ByteUtil.merge(zeroBytes, longBytes);
  }

  private static long bytes32ToLong(byte[] value) {
    return ByteArray.toLong(value);
  }

  class ShieldedKey {
    @Getter
    @Setter
    byte[] sk;
    @Getter
    @Setter
    byte[] ask;
    @Getter
    @Setter
    byte[] nsk;
    @Getter
    @Setter
    byte[] ovk;
    @Getter
    @Setter
    byte[] ak;
    @Getter
    @Setter
    byte[] nk;
    @Getter
    @Setter
    byte[] ivk;
    @Getter
    @Setter
    byte[] d;
    @Getter
    @Setter
    String address;

    String getHexSk() {
      return ByteArray.toHexString(sk);
    }

    String getHexAsk() {
      return ByteArray.toHexString(ask);
    }

    String getHexNsk() {
      return ByteArray.toHexString(nsk);
    }

    String getHexOvk() {
      return ByteArray.toHexString(ovk);
    }

    String getHexAk() {
      return ByteArray.toHexString(ak);
    }

    String getHexNk() {
      return ByteArray.toHexString(nk);
    }

    String getHexIvk() {
      return ByteArray.toHexString(ivk);
    }

    String getHexD() {
      return ByteArray.toHexString(d);
    }

    String getKioAddress() throws ZksnarkException {
      IncomingViewingKey incomingViewingKey = new IncomingViewingKey(ivk);
      return KeyIo.encodePaymentAddress(incomingViewingKey.address(new DiversifierT(d)).get());
    }
  }
}
