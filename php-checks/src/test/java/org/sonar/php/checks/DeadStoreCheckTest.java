package org.sonar.php.checks;

import org.junit.Test;
import org.sonar.plugins.php.CheckVerifier;

import static org.junit.Assert.*;

public class DeadStoreCheckTest {
  @Test
  public void test() throws Exception {
    CheckVerifier.verify(new DeadStoreCheck(), "DeadStoreCheck.php");
  }
}