package org.linda.keystore;


import org.linda.common.crypto.SignInterface;

public interface Credentials {
  SignInterface getPair();

  String getAddress();
}
