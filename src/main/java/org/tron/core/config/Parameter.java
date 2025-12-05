package org.linda.core.config;

public interface Parameter {

  interface CommonConstant {
    byte ADD_PRE_FIX_BYTE_MAINNET = (byte) 0x30;   //30 + address
    byte ADD_PRE_FIX_BYTE_TESTNET = (byte) 0xa1;   //a1 + address
    int ADDRESS_SIZE = 21;
  }

}
