/*
 * Copyright 2009 Red Hat, Inc.
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.hornetq.core.journal;

import java.util.List;
import java.util.Map;

import org.hornetq.core.journal.impl.JournalFile;
import org.hornetq.core.server.HornetQComponent;

/**
 *
 * Most methods on the journal provide a blocking version where you select the sync mode and a non blocking mode where you pass a completion callback as a parameter.
 *
 * Notice also that even on the callback methods it's possible to pass the sync mode. That will only make sense on the NIO operations.
 *
 * @author <a href="mailto:tim.fox@jboss.com">Tim Fox</a>
 * @author <a href="mailto:clebert.suconic@jboss.com">Clebert Suconic</a>
 *
 */
public interface Journal extends HornetQComponent
{
   // Non transactional operations

   void appendAddRecord(long id, byte recordType, byte[] record, boolean sync) throws Exception;

   void appendAddRecord(long id, byte recordType, byte[] record, boolean sync, IOCompletion completionCallback) throws Exception;

   void appendAddRecord(long id, byte recordType, EncodingSupport record, boolean sync) throws Exception;

   void appendAddRecord(long id, byte recordType, EncodingSupport record, boolean sync, IOCompletion completionCallback) throws Exception;

   void appendUpdateRecord(long id, byte recordType, byte[] record, boolean sync) throws Exception;

   void appendUpdateRecord(long id, byte recordType, byte[] record, boolean sync, IOCompletion completionCallback) throws Exception;

   void appendUpdateRecord(long id, byte recordType, EncodingSupport record, boolean sync) throws Exception;

   void appendUpdateRecord(long id,
                           byte recordType,
                           EncodingSupport record,
                           boolean sync,
                           IOCompletion completionCallback) throws Exception;

   void appendDeleteRecord(long id, boolean sync) throws Exception;

   void appendDeleteRecord(long id, boolean sync, IOCompletion completionCallback) throws Exception;

   // Transactional operations

   void appendAddRecordTransactional(long txID, long id, byte recordType, byte[] record) throws Exception;

   void appendAddRecordTransactional(long txID, long id, byte recordType, EncodingSupport record) throws Exception;

   void appendUpdateRecordTransactional(long txID, long id, byte recordType, byte[] record) throws Exception;

   void appendUpdateRecordTransactional(long txID, long id, byte recordType, EncodingSupport record) throws Exception;

   void appendDeleteRecordTransactional(long txID, long id, byte[] record) throws Exception;

   void appendDeleteRecordTransactional(long txID, long id, EncodingSupport record) throws Exception;

   void appendDeleteRecordTransactional(long txID, long id) throws Exception;

   void appendCommitRecord(long txID, boolean sync) throws Exception;

   void appendCommitRecord(long txID, boolean sync, IOCompletion callback) throws Exception;

   /**
    * @param txID
    * @param sync
    * @param callback
    * @param useLineUp if appendCommitRecord should call a storeLineUp. This is because the caller may have already taken into account
    * @throws Exception
    */
   void appendCommitRecord(long txID, boolean sync, IOCompletion callback, boolean lineUpContext) throws Exception;

   /**
    *
    * <p>If the system crashed after a prepare was called, it should store information that is required to bring the transaction
    *     back to a state it could be committed. </p>
    *
    * <p> transactionData allows you to store any other supporting user-data related to the transaction</p>
    *
    * @param txID
    * @param transactionData - extra user data for the prepare
    * @throws Exception
    */
   void appendPrepareRecord(long txID, EncodingSupport transactionData, boolean sync) throws Exception;

   void appendPrepareRecord(long txID, EncodingSupport transactionData, boolean sync, IOCompletion callback) throws Exception;

   void appendPrepareRecord(long txID, byte[] transactionData, boolean sync) throws Exception;

   void appendPrepareRecord(long txID, byte[] transactionData, boolean sync, IOCompletion callback) throws Exception;

   void appendRollbackRecord(long txID, boolean sync) throws Exception;

   void appendRollbackRecord(long txID, boolean sync, IOCompletion callback) throws Exception;

   // Load

   JournalLoadInformation load(LoaderCallback reloadManager) throws Exception;

   /**
    * Load internal data structures and not expose any data. This is only useful if you're using the
    * journal but not interested on the current data. Useful in situations where the journal is
    * being replicated, copied... etc.
    */
   JournalLoadInformation loadInternalOnly() throws Exception;

   /**
    * Load internal data structures, and remain waiting for synchronization to complete.
    */
   JournalLoadInformation loadSyncOnly() throws Exception;

   void lineUpContex(IOCompletion callback);

   JournalLoadInformation load(List<RecordInfo> committedRecords,
                               List<PreparedTransactionInfo> preparedTransactions,
                               TransactionFailureCallback transactionFailure) throws Exception;

   int getAlignment() throws Exception;

   int getNumberOfRecords();

   int getUserVersion();

   void perfBlast(int pages);

   void runDirectJournalBlast() throws Exception;

   /**
    * Reserves journal file IDs, creates the necessary files for synchronization, and places
    * references to these (reserved for sync) files in the map.
    * <p>
    * During the synchronization between a live server and backup, we reserve in the backup the
    * journal file IDs used in the live server. This call also makes sure the files are created
    * empty without any kind of headers added.
    * @param fileIds IDs to reserve for synchronization
    * @return map to be filled with id and journal file pairs for <b>synchronization</b>.
    * @throws Exception
    */
   Map<Long, JournalFile> createFilesForBackupSync(long[] fileIds) throws Exception;

   /**
    * Write lock the Journal and write lock the compacting process. Necessary only during
    * replication for backup synchronization.
    */
   void synchronizationLock();

   /**
    * Unlock the Journal and the compacting process.
    * @see Journal#synchronizationLock()
    */
   void synchronizationUnlock();

   /**
    * Force the usage of a new {@link JournalFile}.
    * @throws Exception
    */
   void forceMoveNextFile() throws Exception;

   /**
    * Returns the {@link JournalFile}s in use.
    * @return array with all {@link JournalFile}s in use
    */
   JournalFile[] getDataFiles();

   SequentialFileFactory getFileFactory();

   int getFileSize();
}
