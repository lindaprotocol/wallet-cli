package org.linda.core.zen;

import static org.linda.common.utils.Utils.failedHighlight;
import static org.linda.common.utils.Utils.greenBoldHighlight;

import com.google.protobuf.ByteString;
import com.typesafe.config.Config;
import io.netty.util.internal.StringUtil;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.ArrayUtils;
import org.linda.api.GrpcAPI.*;
import org.linda.api.GrpcAPI.DecryptNotesLRC20;
import org.linda.common.utils.Base58;
import org.linda.common.utils.ByteArray;
import org.linda.common.utils.ByteUtil;
import org.linda.common.utils.Utils;
import org.linda.core.config.Configuration;
import org.linda.core.exception.CipherException;
import org.linda.core.exception.ZksnarkException;
import org.linda.core.zen.address.KeyIo;
import org.linda.core.zen.address.PaymentAddress;
import org.linda.keystore.SKeyCapsule;
import org.linda.keystore.SKeyEncryptor;
import org.linda.keystore.StringUtils;
import org.linda.keystore.WalletUtils;
import org.linda.protos.Protocol.Block;
import org.linda.walletcli.Client;
import org.linda.walletserver.WalletApi;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class ShieldedLRC20Wrapper {

  private static String prefixFolder;
  private static String lrc20ContractAddress;
  private static String shieldedLRC20ContractAddress;
  private static String ivkAndNumFileName;
  private static String unspendNoteFileName;
  private static String spendNoteFileName;
  private static String shieldedAddressFileName;
  private static String shieldedSkeyFileName;
  private static BigInteger scalingFactor;
  private static AtomicLong nodeIndex = new AtomicLong(0L);
  private Thread thread;

  private byte[] shieldedSkey;
  private static ShieldedLRC20Wrapper instance;

  @Setter
  @Getter
  Map<String, ShieldedAddressInfo> shieldedAddressInfoMap = new ConcurrentHashMap();
  @Setter
  private boolean resetNote = false;
  @Getter
  @Setter
  public Map<String, Long> ivkMapScanBlockNum = new ConcurrentHashMap();
  @Getter
  @Setter
  public Map<Long, ShieldedLRC20NoteInfo> utxoMapNote = new ConcurrentHashMap();
  @Getter
  @Setter
  public List<ShieldedLRC20NoteInfo> spendUtxoList = new ArrayList<>();
  @Getter
  @Setter
  public static long defaultBlockNumberToScan = 0;

  private boolean loadShieldedStatus = false;

  static {
    Config config = Configuration.getByPath("config.conf");
    if (config.hasPath("blockNumberStartToScan")) {
      try {
        defaultBlockNumberToScan = config.getLong("blockNumberStartToScan");
      } catch (Exception e) {
      }
    }
  }

  private ShieldedLRC20Wrapper() {
    thread = new Thread(new scanIvkRunable());
  }

  public static ShieldedLRC20Wrapper getInstance() {
    if (instance == null) {
      instance = new ShieldedLRC20Wrapper();
    }
    return instance;
  }

  public static boolean isSetShieldedLRC20WalletPath() {
    return !(prefixFolder == null || lrc20ContractAddress == null
        || shieldedLRC20ContractAddress == null || ivkAndNumFileName == null
        || unspendNoteFileName == null || spendNoteFileName == null
        || shieldedAddressFileName == null || shieldedSkeyFileName == null);
  }

  public void setShieldedLRC20WalletPath(String contractAddress,
                                         String shieldedContractAddress) {
    if (contractAddress == null || shieldedContractAddress == null
        || !contractAddress.equals(lrc20ContractAddress)
        || !shieldedContractAddress.equals(shieldedLRC20ContractAddress)) {
      loadShieldedStatus = false;
      shieldedSkey = null;
      lrc20ContractAddress = contractAddress;
      shieldedLRC20ContractAddress = shieldedContractAddress;
      prefixFolder = "WalletShieldedLRC20Contract/"
          + lrc20ContractAddress + "_" + shieldedLRC20ContractAddress;
      ivkAndNumFileName = prefixFolder + "/scanblocknumber";
      unspendNoteFileName = prefixFolder + "/unspendnote";
      spendNoteFileName = prefixFolder + "/spendnote";
      shieldedAddressFileName = prefixFolder + "/shieldedaddress";
      shieldedSkeyFileName = prefixFolder + "/shieldedskey.json";
    }
  }

  public void setScalingFactor(BigInteger factor) {
    scalingFactor = factor;
  }
  public BigInteger getScalingFactor() {
    return scalingFactor;
  }

  public String getShieldedLRC20ContractAddress() {
    return shieldedLRC20ContractAddress;
  }

  public String getLRC20ContractAddress() {
    return lrc20ContractAddress;
  }

  public boolean ifShieldedLRC20WalletLoaded() {
    return loadShieldedStatus;
  }

  private void loadWalletFile() throws CipherException {
    loadAddressFromFile();
    loadIvkFromFile();
    loadUnSpendNoteFromFile();
    loadSpendNoteFromFile();
  }

  public boolean loadShieldLRC20Wallet() throws CipherException, IOException {
    if (ifShieldedLRC20WalletLoaded()) {
      return true;
    }

    if (!shieldedSkeyFileExist()) {
      System.out.println("shieldedLRC20 wallet does not exist. Please use " +
          "ImportShieldedLRC20Wallet command to import a shielded LRC20 wallet, or " +
          "GenerateShieldedLRC20Address to generate one.");
      return false;
    }

    if (ArrayUtils.isEmpty(shieldedSkey)) {
      shieldedSkey = loadSkey();
    }

    if (ArrayUtils.isEmpty(shieldedSkey)) {
      return false;
    }

    loadWalletFile();

    if (!thread.isAlive()) {
      thread.start();
    }
    loadShieldedStatus = true;

    return true;
  }

  public class scanIvkRunable implements Runnable {
    public void run() {
      int count = 24;
      for (; ; ) {
        if (!ifShieldedLRC20WalletLoaded()) {
          try {
            Thread.sleep(500);
          } catch (Exception e) {
          }
          continue;
        }
        try {
          updateNoteWhetherSpend();
          scanBlockByIvk();
        } catch (Exception e) {
          ++count;
          if (count >= 24) {
            if (e.getMessage() != null) {
              System.out.println(e.getMessage());
            }
            System.out.println("Please user command resetShieldedLRC20Note to reset notes!!");
            count = 0;
          }
        } finally {
          try {
            //wait for 2.5 seconds
            for (int i = 0; i < 5; ++i) {
              Thread.sleep(500);
              if (resetNote) {
                resetShieldedLRC20Note();
                resetNote = false;
                count = 0;
                System.out.println("Reset shieldedLRC20 note success!");
              }
            }
          } catch (Exception e) {
          }
        }
      }
    }
  }

  private void resetShieldedLRC20Note() throws ZksnarkException {
    ivkMapScanBlockNum.clear();
    for (Entry<String, ShieldedAddressInfo> entry : getShieldedAddressInfoMap().entrySet()) {
      byte[] key = ByteUtil.merge(entry.getValue().getIvk(),
          entry.getValue().getFullViewingKey().getAk(),
          entry.getValue().getFullViewingKey().getNk());
      ivkMapScanBlockNum.put(ByteArray.toHexString(key), defaultBlockNumberToScan);
    }

    utxoMapNote.clear();
    spendUtxoList.clear();

    ZenUtils.clearFile(ivkAndNumFileName);
    ZenUtils.clearFile(unspendNoteFileName);
    ZenUtils.clearFile(spendNoteFileName);
    nodeIndex.set(0L);

    updateIvkAndBlockNumFile();
  }

  private void scanBlockByIvk() throws CipherException {
    Block block = WalletApi.getBlock(-1);
    if (block != null) {
      long blockNum = block.getBlockHeader().toBuilder().getRawData().getNumber();
      for (Entry<String, Long> entry : ivkMapScanBlockNum.entrySet()) {
        byte[] key = ByteArray.fromHexString(entry.getKey());
        byte[] ivk = ByteArray.subArray(key, 0, 32);
        byte[] ak = ByteArray.subArray(key, 32, 64);
        byte[] nk = ByteArray.subArray(key, 64, 96);
        //find a shieldedAddressInfo whose ivk is equal to this ivk
        ShieldedAddressInfo sampleAdressInfo = getShieldedAddressInfoFromIvk(ivk);
        long start = entry.getValue();
        long end = start;
        while (end < blockNum) {
          if (blockNum - start > 200) { // scan 200 blocks at a time
            end = start + 200;
          } else {
            end = blockNum;
          }

          IvkDecryptLRC20Parameters.Builder builder = IvkDecryptLRC20Parameters.newBuilder();
          builder.setStartBlockIndex(start);
          builder.setEndBlockIndex(end);
          builder.setShieldedLRC20ContractAddress(
              ByteString.copyFrom(
                  WalletApi.decodeFromBase58Check(
                      getShieldedLRC20ContractAddress())));
          builder.setIvk(ByteString.copyFrom(ivk));
          builder.setAk(ByteString.copyFrom(ak));
          builder.setNk(ByteString.copyFrom(nk));
          Optional<DecryptNotesLRC20> notes = WalletApi.scanShieldedLRC20NoteByIvk(
              builder.build(), false);
          if (notes.isPresent()) {
            int startNum = utxoMapNote.size();
            for (int i = 0; i < notes.get().getNoteTxsList().size(); ++i) {
              DecryptNotesLRC20.NoteTx noteTx = notes.get().getNoteTxsList().get(i);
              ShieldedLRC20NoteInfo noteInfo = new ShieldedLRC20NoteInfo();
              noteInfo.setPaymentAddress(noteTx.getNote().getPaymentAddress());
              noteInfo.setR(noteTx.getNote().getRcm().toByteArray());
              long noteValue = noteTx.getNote().getValue();
              noteInfo.setValue(noteValue);
              noteInfo.setRawValue(BigInteger.valueOf(noteValue).multiply(scalingFactor));
              noteInfo.setLindId(ByteArray.toHexString(noteTx.getTxid().toByteArray()));
              noteInfo.setIndex(noteTx.getIndex());
              noteInfo.setNoteIndex(nodeIndex.getAndIncrement());
              noteInfo.setPosition(noteTx.getPosition());
              noteInfo.setMemo(noteTx.getNote().getMemo().toByteArray());
              boolean isSpent = noteTx.getIsSpent();
              if (!isSpent) {
                utxoMapNote.put(noteInfo.getNoteIndex(), noteInfo);
              } else {
                spendUtxoList.add(noteInfo);
                saveSpendNoteToFile(noteInfo);
              }
              //put note payment address into  shieldedAddressInfoMap
              if (!shieldedAddressInfoMap.containsKey(noteInfo.getPaymentAddress())) {
                PaymentAddress paymentAddress =
                    KeyIo.decodePaymentAddress(noteInfo.getPaymentAddress());
                ShieldedAddressInfo addressInfo = new ShieldedAddressInfo();
                addressInfo.setD(paymentAddress.getD());
                addressInfo.setPkD(paymentAddress.getPkD());
                addressInfo.setSk(sampleAdressInfo.getSk());
                addressInfo.setIvk(sampleAdressInfo.getIvk());
                addressInfo.setOvk(sampleAdressInfo.getOvk());
                appendAddressInfoToFile(addressInfo);
              }
            }
            int endNum = utxoMapNote.size();
            if (endNum > startNum) {
              saveUnspendNoteToFile();
            }
          }
          start = end;
          ivkMapScanBlockNum.put(entry.getKey(), start);
          updateIvkAndBlockNumFile();
        }
      }
    }
  }

  private ShieldedAddressInfo getShieldedAddressInfoFromIvk(byte[] ivk) {
    for (Entry<String, ShieldedAddressInfo> entry : shieldedAddressInfoMap.entrySet()) {
      if (ByteUtil.equals(ivk, entry.getValue().getIvk())) {
        return entry.getValue();
      }
    }
    return null;//It should not return null.
  }

  private void updateNoteWhetherSpend() throws Exception {
    for (Entry<Long, ShieldedLRC20NoteInfo> entry : utxoMapNote.entrySet()) {
      ShieldedLRC20NoteInfo noteInfo = entry.getValue();

      ShieldedAddressInfo addressInfo =
          getShieldedAddressInfoMap().get(noteInfo.getPaymentAddress());
      NfLRC20Parameters.Builder builder = NfLRC20Parameters.newBuilder();
      builder.setAk(ByteString.copyFrom(addressInfo.getFullViewingKey().getAk()));
      builder.setNk(ByteString.copyFrom(addressInfo.getFullViewingKey().getNk()));
      builder.setPosition(noteInfo.getPosition());
      builder.setShieldedLRC20ContractAddress(
          ByteString.copyFrom(
              WalletApi.decodeFromBase58Check(
                  getShieldedLRC20ContractAddress())));

      Note.Builder noteBuild = Note.newBuilder();
      noteBuild.setPaymentAddress(noteInfo.getPaymentAddress());
      noteBuild.setValue(noteInfo.getValue());
      noteBuild.setRcm(ByteString.copyFrom(noteInfo.getR()));
      noteBuild.setMemo(ByteString.copyFrom(noteInfo.getMemo()));
      builder.setNote(noteBuild.build());

      Optional<NullifierResult> result = WalletApi.isShieldedLRC20ContractNoteSpent(
          builder.build(), false);
      if (result.isPresent() && result.get().getIsSpent()) {
        spendNote(entry.getKey());
      }
    }
  }

  /**
   * set some index note is spend
   *
   * @param noteIndex
   * @return
   */
  public boolean spendNote(long noteIndex) throws CipherException {
    ShieldedLRC20NoteInfo noteInfo = utxoMapNote.get(noteIndex);
    if (noteInfo != null) {
      utxoMapNote.remove(noteIndex);
      spendUtxoList.add(noteInfo);

      saveUnspendNoteToFile();
      saveSpendNoteToFile(noteInfo);
    } else {
      System.err.println("Find note failure. index:" + noteIndex);
    }
    return true;
  }

  /**
   * save new shieldedLRC20 address and scan block num
   *
   * @param addressInfo new shieldedLRC20 address
   * @return
   */
  public boolean addNewShieldedLRC20Address(final ShieldedAddressInfo addressInfo,
                                            boolean newAddress)
      throws CipherException, ZksnarkException {
    appendAddressInfoToFile(addressInfo);
    long blockNum = defaultBlockNumberToScan;
    if (newAddress) {
      try {
        Block block = WalletApi.getBlock(-1);
        if (block != null) {
          blockNum = block.getBlockHeader().toBuilder().getRawData().getNumber();
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
    String key = ByteArray.toHexString(ByteUtil.merge(
        addressInfo.getIvk(),
        addressInfo.getFullViewingKey().getAk(),
        addressInfo.getFullViewingKey().getNk()));
    if (!ivkMapScanBlockNum.containsKey(key)) {
      ivkMapScanBlockNum.put(key, blockNum);
      updateIvkAndBlockNum(key, blockNum);
    }
    return true;
  }

  /**
   * append ivk and block num relationship to file tail
   *
   * @param ivk
   * @param blockNum
   * @return
   */
  private boolean updateIvkAndBlockNum(String ivk, long blockNum) {
    if (ArrayUtils.isEmpty(shieldedSkey)) {
      return false;
    }

    synchronized (ivkAndNumFileName) {
      byte[] key = ByteArray.fromHexString(ivk);
      byte[] value = ByteArray.fromLong(blockNum);
      byte[] text = ByteUtil.merge(key, value);
      try {
        byte[] cipherText = ZenUtils.aesCtrEncrypt(text, shieldedSkey);
        String data = Base58.encode(cipherText);
        ZenUtils.appendToFileTail(ivkAndNumFileName, data);
      } catch (CipherException e) {
        e.printStackTrace();
      }
    }
    return true;
  }

  /**
   * update ivk and block num
   *
   * @return
   */
  private boolean updateIvkAndBlockNumFile() {
    if (ArrayUtils.isEmpty(shieldedSkey)) {
      return false;
    }

    synchronized (ivkAndNumFileName) {
      ZenUtils.clearFile(ivkAndNumFileName);
      for (Entry<String, Long> entry : ivkMapScanBlockNum.entrySet()) {
        byte[] key = ByteArray.fromHexString(entry.getKey());
        byte[] value = ByteArray.fromLong(entry.getValue());
        byte[] text = ByteUtil.merge(key, value);
        try {
          byte[] cipherText = ZenUtils.aesCtrEncrypt(text, shieldedSkey);
          String date = Base58.encode(cipherText);
          ZenUtils.appendToFileTail(ivkAndNumFileName, date);
        } catch (CipherException e) {
          e.printStackTrace();
        }
      }
    }
    return true;
  }

  /**
   * load ivk and block num relationship from file
   *
   * @return
   */
  private boolean loadIvkFromFile() {
    if (ArrayUtils.isEmpty(shieldedSkey)) {
      return false;
    }

    ivkMapScanBlockNum.clear();
    if (ZenUtils.checkFileExist(ivkAndNumFileName)) {
      List<String> list = ZenUtils.getListFromFile(ivkAndNumFileName);
      for (int i = 0; i < list.size(); ++i) {
        byte[] cipherText = Base58.decode(list.get(i));
        try {
          byte[] text = ZenUtils.aesCtrDecrypt(cipherText, shieldedSkey);
          byte[] key = Arrays.copyOfRange(text, 0, 96);
          byte[] value = Arrays.copyOfRange(text, 96, text.length);

          ivkMapScanBlockNum.put(ByteArray.toHexString(key), ByteArray.toLong(value));
        } catch (CipherException e) {
          e.printStackTrace();
        }
      }
    }
    return true;
  }

  /**
   * get shielded address list
   *
   * @return
   */
  public List<String> getShieldedLRC20AddressList() {
    List<String> addressList = new ArrayList<>();
    for (Entry<String, ShieldedAddressInfo> entry : shieldedAddressInfoMap.entrySet()) {
      addressList.add(entry.getKey());
    }
    return addressList;
  }

  /**
   * sort by value of UTXO
   *
   * @return
   */
  public List<String> getvalidateSortUtxoList() {
    List<Map.Entry<Long, ShieldedLRC20NoteInfo>> list = new ArrayList<>(utxoMapNote.entrySet());
    Collections.sort(list, (Entry<Long, ShieldedLRC20NoteInfo> o1,
                            Entry<Long, ShieldedLRC20NoteInfo> o2) -> {
      if (o1.getValue().getValue() < o2.getValue().getValue()) {
        return 1;
      } else {
        return -1;
      }
    });

    List<String> utxoList = new ArrayList<>();
    for (Map.Entry<Long, ShieldedLRC20NoteInfo> entry : list) {
      String string = entry.getKey() + " " + entry.getValue().getPaymentAddress() + " ";
      string += entry.getValue().getRawValue().toString();
      string += " ";
      string += entry.getValue().getLindId();
      string += " ";
      string += entry.getValue().getIndex();
      string += " ";
      string += "UnSpend";
      string += " ";
      string += ZenUtils.getMemo(entry.getValue().getMemo());
      utxoList.add(string);
    }
    return utxoList;
  }

  /**
   * update unspend note
   *
   * @return
   */
  private boolean saveUnspendNoteToFile() throws CipherException {
    if (ArrayUtils.isEmpty(shieldedSkey)) {
      return false;
    }

    ZenUtils.clearFile(unspendNoteFileName);
    for (Entry<Long, ShieldedLRC20NoteInfo> entry : utxoMapNote.entrySet()) {
      String date = entry.getValue().encode(shieldedSkey);
      ZenUtils.appendToFileTail(unspendNoteFileName, date);
    }
    return true;
  }

  /**
   * load unspend note from file
   *
   * @return
   */
  private boolean loadUnSpendNoteFromFile() throws CipherException {
    if (ArrayUtils.isEmpty(shieldedSkey)) {
      return false;
    }
    utxoMapNote.clear();

    if (ZenUtils.checkFileExist(unspendNoteFileName)) {
      List<String> list = ZenUtils.getListFromFile(unspendNoteFileName);
      for (int i = 0; i < list.size(); ++i) {
        ShieldedLRC20NoteInfo noteInfo = new ShieldedLRC20NoteInfo();
        noteInfo.decode(list.get(i), shieldedSkey);
        utxoMapNote.put(noteInfo.getNoteIndex(), noteInfo);

        if (noteInfo.getNoteIndex() >= nodeIndex.get()) {
          nodeIndex.set(noteInfo.getNoteIndex() + 1);
        }
      }
    }
    return true;
  }


  /**
   * append spend note to file tail
   *
   * @return
   */
  private boolean saveSpendNoteToFile(ShieldedLRC20NoteInfo noteInfo) throws CipherException {
    if (ArrayUtils.isEmpty(shieldedSkey)) {
      return false;
    }

    String data = noteInfo.encode(shieldedSkey);
    ZenUtils.appendToFileTail(spendNoteFileName, data);
    return true;
  }

  /**
   * load spend note from file
   *
   * @return
   */
  private boolean loadSpendNoteFromFile() throws CipherException {
    if (ArrayUtils.isEmpty(shieldedSkey)) {
      return false;
    }

    spendUtxoList.clear();
    if (ZenUtils.checkFileExist(spendNoteFileName)) {
      List<String> list = ZenUtils.getListFromFile(spendNoteFileName);
      for (int i = 0; i < list.size(); ++i) {
        ShieldedLRC20NoteInfo noteInfo = new ShieldedLRC20NoteInfo();
        noteInfo.decode(list.get(i), shieldedSkey);
        spendUtxoList.add(noteInfo);

        if (noteInfo.getNoteIndex() >= nodeIndex.get()) {
          nodeIndex.set(noteInfo.getNoteIndex() + 1);
        }
      }
    }
    return true;
  }

  /**
   * load shielded address from file
   *
   * @return
   */
  private boolean loadAddressFromFile() throws CipherException {
    if (ArrayUtils.isEmpty(shieldedSkey)) {
      return false;
    }

    shieldedAddressInfoMap.clear();
    if (ZenUtils.checkFileExist(shieldedAddressFileName)) {
      List<String> addressList = ZenUtils.getListFromFile(shieldedAddressFileName);
      for (String addressString : addressList) {
        ShieldedAddressInfo addressInfo = new ShieldedAddressInfo();
        if (addressInfo.decode(addressString, shieldedSkey)) {
          shieldedAddressInfoMap.put(addressInfo.getAddress(), addressInfo);
        } else {
          System.out.println("*******************");
        }
      }
    }
    return true;
  }

  /**
   * put new shielded address to address list
   *
   * @param addressInfo
   * @return
   */
  private boolean appendAddressInfoToFile(final ShieldedAddressInfo addressInfo)
      throws CipherException {
    if (ArrayUtils.isEmpty(shieldedSkey)) {
      return false;
    }

    String shieldedAddress = addressInfo.getAddress();
    if (!StringUtil.isNullOrEmpty(shieldedAddress) &&
        !shieldedAddressInfoMap.containsKey(shieldedAddress)) {
      String addressString = addressInfo.encode(shieldedSkey);
      ZenUtils.appendToFileTail(shieldedAddressFileName, addressString);

      shieldedAddressInfoMap.put(shieldedAddress, addressInfo);
    }
    return true;
  }

  private boolean shieldedSkeyFileExist() {
    File file = new File(shieldedSkeyFileName);
    return file != null && file.exists();
  }

  private byte[] loadSkey() throws IOException, CipherException {
    File file = new File(shieldedSkeyFileName);
    SKeyCapsule skey = WalletUtils.loadSkeyFile(file);

    byte[] passwd = null;
    System.out.println("Please input your password for shieldedLRC20 wallet.");
    for (int i = 6; i > 0; i--) {
      char[] password = Utils.inputPassword(false);
      passwd = StringUtils.char2Byte(password);
      try {
        SKeyEncryptor.validPassword(passwd, skey);
        break;
      } catch (CipherException e) {
        passwd = null;
        System.out.println(e.getMessage());
        System.out.printf("Left times : %d, please try again.\n", i - 1);
        continue;
      }
    }
    if (passwd == null) {
      System.out.println("Load skey " + failedHighlight() + ", you can not use operation for shieldedLRC20 "
          + "transaction.");
      return null;
    }
    return SKeyEncryptor.decrypt2PrivateBytes(passwd, skey);
  }

  private byte[] generateSkey() throws IOException, CipherException {
    File file = new File(shieldedSkeyFileName);
    byte[] skey = new byte[16];
    new SecureRandom().nextBytes(skey);

    System.out.println("ShieldedLRC20 wallet does not exist, will build it.");
    char[] password = Utils.inputPassword2Twice(false);
    byte[] passwd = StringUtils.char2Byte(password);

    SKeyCapsule sKeyCapsule = SKeyEncryptor.createStandard(passwd, skey);
    WalletUtils.generateSkeyFile(sKeyCapsule, file);
    return skey;
  }

  public void initShieldedLRC20WalletFile() throws IOException, CipherException {
    ZenUtils.checkFoldersExist(prefixFolder);

    if (ArrayUtils.isEmpty(shieldedSkey)) {
      if (shieldedSkeyFileExist()) {
        shieldedSkey = loadSkey();
      } else {
        shieldedSkey = generateSkey();
      }
      loadShieldLRC20Wallet();
    }
  }

  public ShieldedAddressInfo backupShieldedLRC20Wallet() throws IOException,
      CipherException {
    ZenUtils.checkFoldersExist(prefixFolder);

    if (shieldedSkeyFileExist()) {
      byte[] tempShieldedKey = loadSkey();
      if (!ArrayUtils.isEmpty(tempShieldedKey)) {
        if (ArrayUtils.isEmpty(shieldedSkey)) {
          shieldedSkey = tempShieldedKey;
          loadShieldLRC20Wallet();
        }
      } else {
        System.out.println("Invalid password.");
        return null;
      }
    } else {
      System.out.println("ShieldedLRC20 wallet does not exist, please build it first.");
      return null;
    }

    if (shieldedAddressInfoMap.size() <= 0) {
      System.out.println("ShieldedLRC20 addresses is empty, please use command to generate "
          + "ShieldedLRC20 address.");
      return null;
    }

    List<ShieldedAddressInfo> shieldedAddressInfoList = new ArrayList(
        shieldedAddressInfoMap.values());
    for (int i = 0; i < shieldedAddressInfoList.size(); i++) {
      System.out.println("The " + (i + 1) + "th shieldedLRC20 address is "
          + shieldedAddressInfoList.get(i).getAddress());
    }

    if (shieldedAddressInfoList.size() == 1) {
      return shieldedAddressInfoList.get(0);
    } else {
      System.out.println("Please choose between " + greenBoldHighlight(1) + " and "
          + greenBoldHighlight(shieldedAddressInfoList.size()));
      Scanner in = new Scanner(System.in);
      while (true) {
        String input = in.nextLine().trim();
        String num = input.split("\\s+")[0];
        int n;
        try {
          n = new Integer(num);
        } catch (NumberFormatException e) {
          System.out.println("Invalid number of " + num);
          System.out.println("Please choose again between 1 and " + shieldedAddressInfoList.size());
          continue;
        }
        if (n < 1 || n > shieldedAddressInfoList.size()) {
          System.out.println("Invalid number of " + num);
          System.out.println("Please choose again between 1 and " + shieldedAddressInfoList.size());
          continue;
        }
        return shieldedAddressInfoList.get(n - 1);
      }
    }
  }

  public byte[] importShieldedLRC20Wallet() throws IOException, CipherException {
    ZenUtils.checkFoldersExist(prefixFolder);

    if (shieldedSkeyFileExist()) {
      byte[] tempShieldedKey = loadSkey();
      if (ArrayUtils.isEmpty(tempShieldedKey)) {
        System.out.println("Invalid password.");
        return null;
      } else {
        shieldedSkey = tempShieldedKey;
      }
    } else {
      shieldedSkey = generateSkey();
    }
    loadShieldLRC20Wallet();

    byte[] result = null;
    System.out.println("Please input shieldedLRC20 wallet hex string. "
        + "such as 'sk d',Max retry time:" + 3);
    int nTime = 0;

    Scanner in = new Scanner(System.in);
    while (nTime < 3) {
      String input = in.nextLine().trim();
      String[] array = Client.getCmd(input.trim());
      if (array.length == 2 && Utils.isHexString(array[0]) && Utils.isHexString(array[1])) {
        System.out.println("Import shieldedLRC20 wallet hex string is : ");
        System.out.println("sk:" + array[0]);
        System.out.println("d :" + array[1]);

        byte[] sk = ByteArray.fromHexString(array[0]);
        byte[] d = ByteArray.fromHexString(array[1]);
        result = new byte[sk.length + d.length];
        System.arraycopy(sk, 0, result, 0, sk.length);
        System.arraycopy(d, 0, result, sk.length, d.length);
        break;
      }

      StringUtils.clear(result);
      System.out.println("Invalid shieldedLRC20 wallet hex string, please input again.");
      ++nTime;
    }
    return result;
  }
}
