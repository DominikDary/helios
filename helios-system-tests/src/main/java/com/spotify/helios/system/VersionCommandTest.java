package com.spotify.helios.system;

import com.spotify.helios.common.Json;
import com.spotify.helios.common.protocol.VersionResponse;

import org.junit.Test;

import static com.spotify.helios.common.Version.POM_VERSION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VersionCommandTest extends SystemTestBase {

  @Test
  public void testReadableVersion() throws Exception {
    startDefaultMaster();
    final String response = main("version", "-z", getMasterEndpoint()).toString();
    // instead of testing exact output which would break on formatting changes, match regexp
    // to verify output contains two version numbers (one for client, one for master)
    final String regexp = String.format("(?s).*%s.*%s.*", POM_VERSION, POM_VERSION);
    assertTrue("response does not contain two version numbers - \n" + response,
               response.matches(regexp));
    }

  @Test
  public void testJsonVersion() throws Exception {
    startDefaultMaster();
    final VersionResponse version  = getVersion("version", "--json", "-z", getMasterEndpoint());
    assertEquals("wrong client version", POM_VERSION, version.getClientVersion());
    assertEquals("wrong master version", POM_VERSION, version.getMasterVersion());
  }

  @Test
  public void testVersionWithFailedConnection() throws Exception {
    startDefaultMaster();
    // If we fail to connect to master, we should still get the correct client version, and a nice
    // error message instead of master version. Specify bogus endpoint to make this happen.
    final VersionResponse version = getVersion("version", "--json", "-z", "-1");
    assertEquals("wrong client version", POM_VERSION, version.getClientVersion());
    assertEquals("wrong master version", "Unable to connect to master",
                 version.getMasterVersion());
  }

  @Test
  public void testVersionWithServerError() throws Exception {
    startDefaultMaster();
    // If master returns with an error, we should still get the correct client version, and a
    // nice error message instead of master version. Specify bogus path to make this happen.
    final VersionResponse version =
        getVersion("version", "--json", "-z", getMasterEndpoint() + "/badPath");
    assertEquals("wrong client version", POM_VERSION, version.getClientVersion());
    assertEquals("wrong master version", "Master replied with error code 404",
                 version.getMasterVersion());
  }

  private VersionResponse getVersion(String... args) throws Exception {
    final String response = main(args).toString();
    return Json.read(response, VersionResponse.class);
  }

}