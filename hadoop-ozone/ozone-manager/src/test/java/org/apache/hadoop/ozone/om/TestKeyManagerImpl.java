/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.om;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.ReplicationFactor;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.ReplicationType;
import org.apache.hadoop.hdds.scm.exceptions.SCMException;
import org.apache.hadoop.hdds.scm.exceptions.SCMException.ResultCodes;
import org.apache.hadoop.hdds.scm.protocol.ScmBlockLocationProtocol;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.ozone.om.codec.OmBucketInfoCodec;
import org.apache.hadoop.ozone.om.codec.OmKeyInfoCodec;
import org.apache.hadoop.ozone.om.codec.OmVolumeArgsCodec;
import org.apache.hadoop.ozone.om.exceptions.OMException;
import org.apache.hadoop.ozone.om.helpers.OmBucketInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyArgs;
import org.apache.hadoop.ozone.om.helpers.OmKeyInfo;
import org.apache.hadoop.ozone.om.helpers.OmVolumeArgs;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.KeyInfo;
import org.apache.hadoop.test.LambdaTestUtils;
import org.apache.hadoop.util.Time;
import org.apache.hadoop.utils.db.CodecRegistry;
import org.apache.hadoop.utils.db.RDBStore;
import org.apache.hadoop.utils.db.Table;
import org.apache.hadoop.utils.db.TableConfig;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.Statistics;
import org.rocksdb.StatsLevel;

/**
 * Test class for @{@link KeyManagerImpl}.
 * */
public class TestKeyManagerImpl {

  private static KeyManagerImpl keyManager;
  private static ScmBlockLocationProtocol scmBlockLocationProtocol;
  private static OzoneConfiguration conf;
  private static OMMetadataManager metadataManager;
  private static long blockSize = 1000;
  private static final String KEY_NAME = "key1";
  private static final String BUCKET_NAME = "bucket1";
  private static final String VOLUME_NAME = "vol1";
  private static RDBStore rdbStore = null;
  private static Table<String, OmKeyInfo> keyTable = null;
  private static Table<String, OmBucketInfo> bucketTable = null;
  private static Table<String, OmVolumeArgs> volumeTable = null;
  private static DBOptions options = null;
  private KeyInfo keyData;
  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  @Before
  public void setUp() throws Exception {
    conf = new OzoneConfiguration();
    scmBlockLocationProtocol = Mockito.mock(ScmBlockLocationProtocol.class);
    metadataManager = Mockito.mock(OMMetadataManager.class);
    keyManager = new KeyManagerImpl(scmBlockLocationProtocol, metadataManager,
        conf, "om1", null);
    setupMocks();
  }

  private void setupMocks() throws Exception {
    Mockito.when(scmBlockLocationProtocol
        .allocateBlock(Mockito.anyLong(), Mockito.any(ReplicationType.class),
            Mockito.any(ReplicationFactor.class), Mockito.anyString()))
        .thenThrow(
            new SCMException("ChillModePrecheck failed for allocateBlock",
                ResultCodes.CHILL_MODE_EXCEPTION));
    setupRocksDb();
    Mockito.when(metadataManager.getVolumeTable()).thenReturn(volumeTable);
    Mockito.when(metadataManager.getBucketTable()).thenReturn(bucketTable);
    Mockito.when(metadataManager.getOpenKeyTable()).thenReturn(keyTable);
    Mockito.when(metadataManager.getLock())
        .thenReturn(new OzoneManagerLock(conf));
    Mockito.when(metadataManager.getVolumeKey(VOLUME_NAME))
        .thenReturn(VOLUME_NAME);
    Mockito.when(metadataManager.getBucketKey(VOLUME_NAME, BUCKET_NAME))
        .thenReturn(BUCKET_NAME);
    Mockito.when(metadataManager.getOpenKey(VOLUME_NAME, BUCKET_NAME,
        KEY_NAME, 1)).thenReturn(KEY_NAME);
  }

  private void setupRocksDb() throws Exception {
    options = new DBOptions();
    options.setCreateIfMissing(true);
    options.setCreateMissingColumnFamilies(true);

    Statistics statistics = new Statistics();
    statistics.setStatsLevel(StatsLevel.ALL);
    options = options.setStatistics(statistics);

    Set<TableConfig> configSet = new HashSet<>();
    for (String name : Arrays
        .asList(DFSUtil.bytes2String(RocksDB.DEFAULT_COLUMN_FAMILY),
            "testKeyTable", "testBucketTable", "testVolumeTable")) {
      TableConfig newConfig = new TableConfig(name, new ColumnFamilyOptions());
      configSet.add(newConfig);
    }
    keyData = KeyInfo.newBuilder()
        .setKeyName(KEY_NAME)
        .setBucketName(BUCKET_NAME)
        .setVolumeName(VOLUME_NAME)
        .setDataSize(blockSize)
        .setType(ReplicationType.STAND_ALONE)
        .setFactor(ReplicationFactor.ONE)
        .setCreationTime(Time.now())
        .setModificationTime(Time.now())
        .build();

    CodecRegistry registry = new CodecRegistry();
    registry.addCodec(OmKeyInfo.class, new OmKeyInfoCodec());
    registry.addCodec(OmVolumeArgs.class, new OmVolumeArgsCodec());
    registry.addCodec(OmBucketInfo.class, new OmBucketInfoCodec());
    rdbStore = new RDBStore(folder.newFolder(), options, configSet, registry);

    keyTable =
        rdbStore.getTable("testKeyTable", String.class, OmKeyInfo.class);

    bucketTable =
        rdbStore.getTable("testBucketTable", String.class, OmBucketInfo.class);

    volumeTable =
        rdbStore.getTable("testVolumeTable", String.class, OmVolumeArgs.class);

    volumeTable.put(VOLUME_NAME, OmVolumeArgs.newBuilder()
        .setAdminName("a")
        .setOwnerName("o")
        .setVolume(VOLUME_NAME)
        .build());

    bucketTable.put(BUCKET_NAME,
        new OmBucketInfo.Builder().setBucketName(BUCKET_NAME)
            .setVolumeName(VOLUME_NAME).build());

    keyTable.put(KEY_NAME, new OmKeyInfo.Builder()
        .setVolumeName(VOLUME_NAME)
        .setBucketName(BUCKET_NAME)
        .setKeyName(KEY_NAME)
        .setReplicationType(ReplicationType.STAND_ALONE)
        .setReplicationFactor(ReplicationFactor.THREE)
        .build());

  }

  @Test
  public void allocateBlockFailureInChillMode() throws Exception {
    OmKeyArgs keyArgs = new OmKeyArgs.Builder().setKeyName(KEY_NAME)
        .setBucketName(BUCKET_NAME)
        .setFactor(ReplicationFactor.ONE)
        .setType(ReplicationType.STAND_ALONE)
        .setVolumeName(VOLUME_NAME).build();
    LambdaTestUtils.intercept(OMException.class,
        "ChillModePrecheck failed for allocateBlock", () -> {
          keyManager.allocateBlock(keyArgs, 1);
        });
  }

  @Test
  public void openKeyFailureInChillMode() throws Exception {
    OmKeyArgs keyArgs = new OmKeyArgs.Builder().setKeyName(KEY_NAME)
        .setBucketName(BUCKET_NAME)
        .setFactor(ReplicationFactor.ONE)
        .setDataSize(1000)
        .setType(ReplicationType.STAND_ALONE)
        .setVolumeName(VOLUME_NAME).build();
    LambdaTestUtils.intercept(OMException.class,
        "ChillModePrecheck failed for allocateBlock", () -> {
          keyManager.openKey(keyArgs);
        });
  }
}