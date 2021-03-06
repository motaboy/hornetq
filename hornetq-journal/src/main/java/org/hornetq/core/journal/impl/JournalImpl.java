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

package org.hornetq.core.journal.impl;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.hornetq.api.core.HornetQBuffer;
import org.hornetq.api.core.HornetQBuffers;
import org.hornetq.api.core.Pair;
import org.hornetq.core.journal.EncodingSupport;
import org.hornetq.core.journal.IOAsyncTask;
import org.hornetq.core.journal.IOCompletion;
import org.hornetq.core.journal.JournalLoadInformation;
import org.hornetq.core.journal.LoaderCallback;
import org.hornetq.core.journal.PreparedTransactionInfo;
import org.hornetq.core.journal.RecordInfo;
import org.hornetq.core.journal.SequentialFile;
import org.hornetq.core.journal.SequentialFileFactory;
import org.hornetq.core.journal.TestableJournal;
import org.hornetq.core.journal.TransactionFailureCallback;
import org.hornetq.core.journal.impl.dataformat.ByteArrayEncoding;
import org.hornetq.core.journal.impl.dataformat.JournalAddRecord;
import org.hornetq.core.journal.impl.dataformat.JournalAddRecordTX;
import org.hornetq.core.journal.impl.dataformat.JournalCompleteRecordTX;
import org.hornetq.core.journal.impl.dataformat.JournalCompleteRecordTX.TX_RECORD_TYPE;
import org.hornetq.core.journal.impl.dataformat.JournalDeleteRecord;
import org.hornetq.core.journal.impl.dataformat.JournalDeleteRecordTX;
import org.hornetq.core.journal.impl.dataformat.JournalInternalRecord;
import org.hornetq.core.journal.impl.dataformat.JournalRollbackRecordTX;
import org.hornetq.journal.HornetQJournalBundle;
import org.hornetq.journal.HornetQJournalLogger;
import org.hornetq.utils.DataConstants;

/**
 *
 * <p>A circular log implementation.</p
 *
 * <p>Look at {@link JournalImpl#load(LoaderCallback)} for the file layout
 *
 * @author <a href="mailto:tim.fox@jboss.com">Tim Fox</a>
 *
 * @author <a href="mailto:clebert.suconic@jboss.com">Clebert Suconic</a>
 *
 */
public class JournalImpl extends JournalBase implements TestableJournal, JournalRecordProvider
{

   // Constants -----------------------------------------------------

   public static final int FORMAT_VERSION = 2;

   private static final int COMPATIBLE_VERSIONS[] = new int[] { 1 };

   // Static --------------------------------------------------------
   private static final boolean trace = HornetQJournalLogger.LOGGER.isTraceEnabled();

   // This is useful at debug time...
   // if you set it to true, all the appends, deletes, rollbacks, commits, etc.. are sent to System.out
   private static final boolean TRACE_RECORDS = trace;

   // This method exists just to make debug easier.
   // I could replace log.trace by log.info temporarily while I was debugging
   // Journal
   private static final void trace(final String message)
   {
      HornetQJournalLogger.LOGGER.trace(message);
   }

   private static final void traceRecord(final String message)
   {
      HornetQJournalLogger.LOGGER.trace(message);
   }

   // The sizes of primitive types

   public static final int MIN_FILE_SIZE = 1024;

   // FileID(Long) + JournalVersion + UserVersion
   public static final int SIZE_HEADER = DataConstants.SIZE_LONG + DataConstants.SIZE_INT + DataConstants.SIZE_INT;

   private static final int BASIC_SIZE = DataConstants.SIZE_BYTE + DataConstants.SIZE_INT + DataConstants.SIZE_INT;

   public static final int SIZE_ADD_RECORD = JournalImpl.BASIC_SIZE + DataConstants.SIZE_LONG +
                                             DataConstants.SIZE_BYTE +
                                             DataConstants.SIZE_INT /* + record.length */;

   // Record markers - they must be all unique

   public static final byte ADD_RECORD = 11;

   public static final byte UPDATE_RECORD = 12;

   public static final int SIZE_ADD_RECORD_TX = JournalImpl.BASIC_SIZE + DataConstants.SIZE_LONG +
                                                DataConstants.SIZE_BYTE +
                                                DataConstants.SIZE_LONG +
                                                DataConstants.SIZE_INT /* + record.length */;

   public static final byte ADD_RECORD_TX = 13;

   public static final byte UPDATE_RECORD_TX = 14;

   public static final int SIZE_DELETE_RECORD_TX = JournalImpl.BASIC_SIZE + DataConstants.SIZE_LONG +
                                                   DataConstants.SIZE_LONG +
                                                   DataConstants.SIZE_INT /* + record.length */;

   public static final byte DELETE_RECORD_TX = 15;

   public static final int SIZE_DELETE_RECORD = JournalImpl.BASIC_SIZE + DataConstants.SIZE_LONG;

   public static final byte DELETE_RECORD = 16;

   public static final int SIZE_COMPLETE_TRANSACTION_RECORD = JournalImpl.BASIC_SIZE + DataConstants.SIZE_LONG +
                                                              DataConstants.SIZE_INT;

   public static final int SIZE_PREPARE_RECORD = JournalImpl.SIZE_COMPLETE_TRANSACTION_RECORD + DataConstants.SIZE_INT;

   public static final byte PREPARE_RECORD = 17;

   public static final int SIZE_COMMIT_RECORD = JournalImpl.SIZE_COMPLETE_TRANSACTION_RECORD;

   public static final byte COMMIT_RECORD = 18;

   public static final int SIZE_ROLLBACK_RECORD = JournalImpl.BASIC_SIZE + DataConstants.SIZE_LONG;

   public static final byte ROLLBACK_RECORD = 19;

   protected static final byte FILL_CHARACTER = (byte)'J';

   // Attributes ----------------------------------------------------

   private volatile boolean autoReclaim = true;

   private final int userVersion;

   private final int minFiles;

   private final float compactPercentage;

   private final int compactMinFiles;

   private final SequentialFileFactory fileFactory;

   private final JournalFilesRepository filesRepository;

   // Compacting may replace this structure
   private final ConcurrentMap<Long, JournalRecord> records = new ConcurrentHashMap<Long, JournalRecord>();

   // Compacting may replace this structure
   private final ConcurrentMap<Long, JournalTransaction> transactions = new ConcurrentHashMap<Long, JournalTransaction>();

   // This will be set only while the JournalCompactor is being executed
   private volatile JournalCompactor compactor;

   private final AtomicBoolean compactorEnabled = new AtomicBoolean();

   private ExecutorService filesExecutor = null;

   private ExecutorService compactorExecutor = null;

   // Lock used during the append of records
   // This lock doesn't represent a global lock.
   // After a record is appended, the usedFile can't be changed until the positives and negatives are updated
   private final ReentrantLock lockAppend = new ReentrantLock();

   /**
    * We don't lock the journal during the whole compacting operation. During compacting we only
    * lock it (i) when gathering the initial structure, and (ii) when replicating the structures
    * after finished compacting.
    * <p>
    * However we need to lock it while taking and updating snapshots
    */
   private final ReadWriteLock journalLock = new ReentrantReadWriteLock();
   private final ReadWriteLock compactorLock = new ReentrantReadWriteLock();

   private volatile JournalFile currentFile;

   private volatile JournalState state = JournalState.STOPPED;

   private final Reclaimer reclaimer = new Reclaimer();

   // Constructors --------------------------------------------------

   public JournalImpl(final int fileSize,
                      final int minFiles,
                      final int compactMinFiles,
                      final int compactPercentage,
                      final SequentialFileFactory fileFactory,
                      final String filePrefix,
                      final String fileExtension,
                      final int maxAIO)
                         {
      this(fileSize, minFiles, compactMinFiles, compactPercentage, fileFactory, filePrefix, fileExtension, maxAIO, 0);
   }


      public JournalImpl(final int fileSize,
                      final int minFiles,
                      final int compactMinFiles,
                      final int compactPercentage,
                      final SequentialFileFactory fileFactory,
                      final String filePrefix,
                      final String fileExtension,
                      final int maxAIO, final int userVersion)
   {
      super(fileFactory.isSupportsCallbacks(), fileSize);
      if (fileSize % fileFactory.getAlignment() != 0)
      {
         throw new IllegalArgumentException("Invalid journal-file-size " + fileSize + ", It should be multiple of " +
                                            fileFactory.getAlignment());
      }
      if (minFiles < 2)
      {
         throw new IllegalArgumentException("minFiles cannot be less than 2");
      }
      if (compactPercentage < 0 || compactPercentage > 100)
      {
         throw new IllegalArgumentException("Compact Percentage out of range");
      }

      if (compactPercentage == 0)
      {
         this.compactPercentage = 0;
      }
      else
      {
         this.compactPercentage = compactPercentage / 100f;
      }

      this.compactMinFiles = compactMinFiles;
      this.minFiles = minFiles;

      this.fileFactory = fileFactory;

      filesRepository = new JournalFilesRepository(fileFactory,
                                                   this,
                                                   filePrefix,
                                                   fileExtension,
                                                   userVersion,
                                                   maxAIO,
                                                   fileSize,
                                                   minFiles);

      this.userVersion = userVersion;
   }

   @Override
   public String toString()
   {
      return "JournalImpl(state=" + state + ", currentFile=[" + currentFile + "], hash=" + super.toString() + ")";
   }

   public void runDirectJournalBlast() throws Exception
   {
      final int numIts = 100000000;

      HornetQJournalLogger.LOGGER.runningJournalBlast(numIts);

      final CountDownLatch latch = new CountDownLatch(numIts * 2);

      class MyIOAsyncTask implements IOCompletion
      {
         public void done()
         {
            latch.countDown();
         }

         public void onError(final int errorCode, final String errorMessage)
         {

         }

         public void storeLineUp()
         {
         }
      }

      final MyIOAsyncTask task = new MyIOAsyncTask();

      final int recordSize = 1024;

      final byte[] bytes = new byte[recordSize];

      class MyRecord implements EncodingSupport
      {

         public void decode(final HornetQBuffer buffer)
         {
         }

         public void encode(final HornetQBuffer buffer)
         {
            buffer.writeBytes(bytes);
         }

         public int getEncodeSize()
         {
            return recordSize;
         }

      }

      MyRecord record = new MyRecord();

      for (int i = 0; i < numIts; i++)
      {
         appendAddRecord(i, (byte)1, record, true, task);
         appendDeleteRecord(i, true, task);
      }

      latch.await();
   }

   public Map<Long, JournalRecord> getRecords()
   {
      return records;
   }

   public JournalFile getCurrentFile()
   {
      return currentFile;
   }

   public JournalCompactor getCompactor()
   {
      return compactor;
   }

   /** this method is used internally only however tools may use it to maintenance.
    *  It won't be part of the interface as the tools should be specific to the implementation */
   public List<JournalFile> orderFiles() throws Exception
   {
      List<String> fileNames = fileFactory.listFiles(filesRepository.getFileExtension());

      List<JournalFile> orderedFiles = new ArrayList<JournalFile>(fileNames.size());

      for (String fileName : fileNames)
      {
         SequentialFile file = fileFactory.createSequentialFile(fileName, filesRepository.getMaxAIO());

         file.open(1, false);

         try
         {

            JournalFileImpl jrnFile = readFileHeader(file);

            orderedFiles.add(jrnFile);
         }
         finally
         {
            file.close();
         }
      }

      // Now order them by ordering id - we can't use the file name for ordering
      // since we can re-use dataFiles

      Collections.sort(orderedFiles, new JournalFileComparator());

      return orderedFiles;
   }

   /** this method is used internally only however tools may use it to maintenance.  */
   public static int readJournalFile(final SequentialFileFactory fileFactory,
                                     final JournalFile file,
                                     final JournalReaderCallback reader) throws Exception
   {
      file.getFile().open(1, false);
      ByteBuffer wholeFileBuffer = null;
      try
      {
         final int filesize = (int)file.getFile().size();

         wholeFileBuffer = fileFactory.newBuffer(filesize);

         final int journalFileSize = file.getFile().read(wholeFileBuffer);

         if (journalFileSize != filesize)
         {
            throw new RuntimeException("Invalid read! The system couldn't read the entire file into memory");
         }

         // First long is the ordering timestamp, we just jump its position
         wholeFileBuffer.position(JournalImpl.SIZE_HEADER);

         int lastDataPos = JournalImpl.SIZE_HEADER;

         while (wholeFileBuffer.hasRemaining())
         {
            final int pos = wholeFileBuffer.position();

            byte recordType = wholeFileBuffer.get();

            if (recordType < JournalImpl.ADD_RECORD || recordType > JournalImpl.ROLLBACK_RECORD)
            {
               // I - We scan for any valid record on the file. If a hole
               // happened on the middle of the file we keep looking until all
               // the possibilities are gone
               continue;
            }

            if (JournalImpl.isInvalidSize(journalFileSize, wholeFileBuffer.position(), DataConstants.SIZE_INT))
            {
               reader.markAsDataFile(file);

               wholeFileBuffer.position(pos + 1);
               // II - Ignore this record, lets keep looking
               continue;
            }

            // III - Every record has the file-id.
            // This is what supports us from not re-filling the whole file
            int readFileId = wholeFileBuffer.getInt();

            // This record is from a previous file-usage. The file was
            // reused and we need to ignore this record
            if (readFileId != file.getRecordID())
            {
               wholeFileBuffer.position(pos + 1);
               continue;
            }

            short compactCount = 0;

            if (file.getJournalVersion() >= 2)
            {
               if (JournalImpl.isInvalidSize(journalFileSize, wholeFileBuffer.position(), DataConstants.SIZE_BYTE))
               {
                  reader.markAsDataFile(file);

                  wholeFileBuffer.position(pos + 1);
                  continue;
               }

               compactCount = wholeFileBuffer.get();
            }

            long transactionID = 0;

            if (JournalImpl.isTransaction(recordType))
            {
               if (JournalImpl.isInvalidSize(journalFileSize, wholeFileBuffer.position(), DataConstants.SIZE_LONG))
               {
                  wholeFileBuffer.position(pos + 1);
                  reader.markAsDataFile(file);
                  continue;
               }

               transactionID = wholeFileBuffer.getLong();
            }

            long recordID = 0;

            // If prepare or commit
            if (!JournalImpl.isCompleteTransaction(recordType))
            {
               if (JournalImpl.isInvalidSize(journalFileSize, wholeFileBuffer.position(), DataConstants.SIZE_LONG))
               {
                  wholeFileBuffer.position(pos + 1);
                  reader.markAsDataFile(file);
                  continue;
               }

               recordID = wholeFileBuffer.getLong();
            }

            // We use the size of the record to validate the health of the
            // record.
            // (V) We verify the size of the record

            // The variable record portion used on Updates and Appends
            int variableSize = 0;

            // Used to hold extra data on transaction prepares
            int preparedTransactionExtraDataSize = 0;

            byte userRecordType = 0;

            byte record[] = null;

            if (JournalImpl.isContainsBody(recordType))
            {
               if (JournalImpl.isInvalidSize(journalFileSize, wholeFileBuffer.position(), DataConstants.SIZE_INT))
               {
                  wholeFileBuffer.position(pos + 1);
                  reader.markAsDataFile(file);
                  continue;
               }

               variableSize = wholeFileBuffer.getInt();

               if (recordType != JournalImpl.DELETE_RECORD_TX)
               {
                  if (JournalImpl.isInvalidSize(journalFileSize, wholeFileBuffer.position(), 1))
                  {
                     wholeFileBuffer.position(pos + 1);
                     continue;
                  }

                  userRecordType = wholeFileBuffer.get();
               }

               if (JournalImpl.isInvalidSize(journalFileSize, wholeFileBuffer.position(), variableSize))
               {
                  wholeFileBuffer.position(pos + 1);
                  continue;
               }

               record = new byte[variableSize];

               wholeFileBuffer.get(record);
            }

            // Case this is a transaction, this will contain the number of pendingTransactions on a transaction, at the
            // currentFile
            int transactionCheckNumberOfRecords = 0;

            if (recordType == JournalImpl.PREPARE_RECORD || recordType == JournalImpl.COMMIT_RECORD)
            {
               if (JournalImpl.isInvalidSize(journalFileSize, wholeFileBuffer.position(), DataConstants.SIZE_INT))
               {
                  wholeFileBuffer.position(pos + 1);
                  continue;
               }

               transactionCheckNumberOfRecords = wholeFileBuffer.getInt();

               if (recordType == JournalImpl.PREPARE_RECORD)
               {
                  if (JournalImpl.isInvalidSize(journalFileSize, wholeFileBuffer.position(), DataConstants.SIZE_INT))
                  {
                     wholeFileBuffer.position(pos + 1);
                     continue;
                  }
                  // Add the variable size required for preparedTransactions
                  preparedTransactionExtraDataSize = wholeFileBuffer.getInt();
               }
               variableSize = 0;
            }

            int recordSize = JournalImpl.getRecordSize(recordType, file.getJournalVersion());

            // VI - this is completing V, We will validate the size at the end
            // of the record,
            // But we avoid buffer overflows by damaged data
            if (JournalImpl.isInvalidSize(journalFileSize, pos, recordSize + variableSize +
                                                                preparedTransactionExtraDataSize))
            {
               // Avoid a buffer overflow caused by damaged data... continue
               // scanning for more pendingTransactions...
               JournalImpl.trace("Record at position " + pos +
                                 " recordType = " +
                                 recordType +
                                 " file:" +
                                 file.getFile().getFileName() +
                                 " recordSize: " +
                                 recordSize +
                                 " variableSize: " +
                                 variableSize +
                                 " preparedTransactionExtraDataSize: " +
                                 preparedTransactionExtraDataSize +
                                 " is corrupted and it is being ignored (II)");
               // If a file has damaged pendingTransactions, we make it a dataFile, and the
               // next reclaiming will fix it
               reader.markAsDataFile(file);
               wholeFileBuffer.position(pos + 1);

               continue;
            }

            int oldPos = wholeFileBuffer.position();

            wholeFileBuffer.position(pos + variableSize +
                                     recordSize +
                                     preparedTransactionExtraDataSize -
                                     DataConstants.SIZE_INT);

            int checkSize = wholeFileBuffer.getInt();

            // VII - The checkSize at the end has to match with the size
            // informed at the beginning.
            // This is like testing a hash for the record. (We could replace the
            // checkSize by some sort of calculated hash)
            if (checkSize != variableSize + recordSize + preparedTransactionExtraDataSize)
            {
               JournalImpl.trace("Record at position " + pos +
                                 " recordType = " +
                                 recordType +
                                 " possible transactionID = " +
                                 transactionID +
                                 " possible recordID = " +
                                 recordID +
                                 " file:" +
                                 file.getFile().getFileName() +
                                 " is corrupted and it is being ignored (III)");

               // If a file has damaged pendingTransactions, we make it a dataFile, and the
               // next reclaiming will fix it
               reader.markAsDataFile(file);

               wholeFileBuffer.position(pos + DataConstants.SIZE_BYTE);

               continue;
            }

            wholeFileBuffer.position(oldPos);

            // At this point everything is checked. So we relax and just load
            // the data now.

            switch (recordType)
            {
               case ADD_RECORD:
               {
                  reader.onReadAddRecord(new RecordInfo(recordID, userRecordType, record, false, compactCount));
                  break;
               }

               case UPDATE_RECORD:
               {
                  reader.onReadUpdateRecord(new RecordInfo(recordID, userRecordType, record, true, compactCount));
                  break;
               }

               case DELETE_RECORD:
               {
                  reader.onReadDeleteRecord(recordID);
                  break;
               }

               case ADD_RECORD_TX:
               {
                  reader.onReadAddRecordTX(transactionID, new RecordInfo(recordID,
                                                                         userRecordType,
                                                                         record,
                                                                         false,
                                                                         compactCount));
                  break;
               }

               case UPDATE_RECORD_TX:
               {
                  reader.onReadUpdateRecordTX(transactionID, new RecordInfo(recordID,
                                                                            userRecordType,
                                                                            record,
                                                                            true,
                                                                            compactCount));
                  break;
               }

               case DELETE_RECORD_TX:
               {
                  reader.onReadDeleteRecordTX(transactionID, new RecordInfo(recordID,
                                                                            (byte)0,
                                                                            record,
                                                                            true,
                                                                            compactCount));
                  break;
               }

               case PREPARE_RECORD:
               {

                  byte extraData[] = new byte[preparedTransactionExtraDataSize];

                  wholeFileBuffer.get(extraData);

                  reader.onReadPrepareRecord(transactionID, extraData, transactionCheckNumberOfRecords);

                  break;
               }
               case COMMIT_RECORD:
               {

                  reader.onReadCommitRecord(transactionID, transactionCheckNumberOfRecords);
                  break;
               }
               case ROLLBACK_RECORD:
               {
                  reader.onReadRollbackRecord(transactionID);
                  break;
               }
               default:
               {
                  throw new IllegalStateException("Journal " + file.getFile().getFileName() +
                                                  " is corrupt, invalid record type " +
                                                  recordType);
               }
            }

            checkSize = wholeFileBuffer.getInt();

            // This is a sanity check about the loading code itself.
            // If this checkSize doesn't match, it means the reading method is
            // not doing what it was supposed to do
            if (checkSize != variableSize + recordSize + preparedTransactionExtraDataSize)
            {
               throw new IllegalStateException("Internal error on loading file. Position doesn't match with checkSize, file = " + file.getFile() +
                                               ", pos = " +
                                               pos);
            }

            lastDataPos = wholeFileBuffer.position();

         }

         return lastDataPos;
      }
      catch (Throwable e)
      {
         HornetQJournalLogger.LOGGER.errorReadingFile(e);
         throw new Exception(e.getMessage(), e);
      }
      finally
      {
         if (wholeFileBuffer != null)
         {
            fileFactory.releaseBuffer(wholeFileBuffer);
         }

         try
         {
            file.getFile().close();
         }
         catch (Throwable ignored)
         {
         }
      }
   }

   // Journal implementation
   // ----------------------------------------------------------------

   @Override
   public void appendAddRecord(final long id,
                               final byte recordType,
                               final EncodingSupport record,
                               final boolean sync,
                               final IOCompletion callback) throws Exception
   {
      checkJournalIsLoaded();

      journalLock.readLock().lock();

      try
      {
         JournalInternalRecord addRecord = new JournalAddRecord(true, id, recordType, record);

         if (callback != null)
         {
            callback.storeLineUp();
         }

         lockAppend.lock();
         try
         {
            JournalFile usedFile = appendRecord(addRecord, false, sync, null, callback);

            if (JournalImpl.TRACE_RECORDS)
            {
               JournalImpl.traceRecord("appendAddRecord::id=" + id +
                                       ", userRecordType=" +
                                       recordType +
                                       ", usedFile = " +
                                       usedFile);
            }

            records.put(id, new JournalRecord(usedFile, addRecord.getEncodeSize()));
         }
         finally
         {
            lockAppend.unlock();
         }
      }
      finally
      {
         journalLock.readLock().unlock();
      }
   }

   @Override
   public void appendUpdateRecord(final long id,
                                  final byte recordType,
                                  final EncodingSupport record,
                                  final boolean sync,
                                  final IOCompletion callback) throws Exception
   {
      checkJournalIsLoaded();

      journalLock.readLock().lock();

      try
      {
         JournalRecord jrnRecord = records.get(id);

         if (jrnRecord == null)
         {
            if (!(compactor != null && compactor.lookupRecord(id)))
            {
               throw new IllegalStateException("Cannot find add info " + id);
            }
         }

         JournalInternalRecord updateRecord = new JournalAddRecord(false, id, recordType, record);

         if (callback != null)
         {
            callback.storeLineUp();
         }

         lockAppend.lock();
         try
         {
            JournalFile usedFile = appendRecord(updateRecord, false, sync, null, callback);

            if (JournalImpl.TRACE_RECORDS)
            {
               JournalImpl.traceRecord("appendUpdateRecord::id=" + id +
                                       ", userRecordType=" +
                                       recordType +
                                       ", usedFile = " +
                                       usedFile);
            }

            // record== null here could only mean there is a compactor, and computing the delete should be done after
            // compacting is done
            if (jrnRecord == null)
            {
               compactor.addCommandUpdate(id, usedFile, updateRecord.getEncodeSize());
            }
            else
            {
               jrnRecord.addUpdateFile(usedFile, updateRecord.getEncodeSize());
            }
         }
         finally
         {
            lockAppend.unlock();
         }
      }
      finally
      {
         journalLock.readLock().unlock();
      }
   }


   @Override
   public void appendDeleteRecord(final long id, final boolean sync, final IOCompletion callback) throws Exception
   {
      checkJournalIsLoaded();

      journalLock.readLock().lock();
      try
      {

         JournalRecord record = null;

         if (compactor == null)
         {
            record = records.remove(id);

            if (record == null)
            {
               throw new IllegalStateException("Cannot find add info " + id);
            }
         }
         else
         {
            if (!records.containsKey(id) && !compactor.lookupRecord(id))
            {
               throw new IllegalStateException("Cannot find add info " + id + " on compactor or current records");
            }
         }

         JournalInternalRecord deleteRecord = new JournalDeleteRecord(id);

         if (callback != null)
         {
            callback.storeLineUp();
         }

         lockAppend.lock();
         try
         {
            JournalFile usedFile = appendRecord(deleteRecord, false, sync, null, callback);

            if (JournalImpl.TRACE_RECORDS)
            {
               JournalImpl.traceRecord("appendDeleteRecord::id=" + id + ", usedFile = " + usedFile);
            }

            // record== null here could only mean there is a compactor, and computing the delete should be done after
            // compacting is done
            if (record == null)
            {
               compactor.addCommandDelete(id, usedFile);
            }
            else
            {
               record.delete(usedFile);
            }

         }
         finally
         {
            lockAppend.unlock();
         }
      }
      finally
      {
         journalLock.readLock().unlock();
      }
   }

   @Override
   public void appendAddRecordTransactional(final long txID,
                                            final long id,
                                            final byte recordType,
                                            final EncodingSupport record) throws Exception
   {
      checkJournalIsLoaded();

      journalLock.readLock().lock();

      try
      {
         JournalInternalRecord addRecord = new JournalAddRecordTX(true, txID, id, recordType, record);

         JournalTransaction tx = getTransactionInfo(txID);

         lockAppend.lock();
         try
         {
            JournalFile usedFile = appendRecord(addRecord, false, false, tx, null);

            if (JournalImpl.TRACE_RECORDS)
            {
               JournalImpl.traceRecord("appendAddRecordTransactional:txID=" + txID +
                                       ",id=" +
                                       id +
                                       ", userRecordType=" +
                                       recordType +
                                       ", usedFile = " +
                                       usedFile);
            }

            tx.addPositive(usedFile, id, addRecord.getEncodeSize());
         }
         finally
         {
            lockAppend.unlock();
         }
      }
      finally
      {
         journalLock.readLock().unlock();
      }
   }

   private void checkJournalIsLoaded()
   {
      if (state != JournalState.LOADED && state != JournalState.SYNCING)
      {
         throw new IllegalStateException("Journal must be in state=" + JournalState.LOADED + ", was [" + state + "]");
      }
   }

   private void setJournalState(JournalState newState)
   {
      state = newState;
   }

   @Override
   public void appendUpdateRecordTransactional(final long txID,
                                               final long id,
                                               final byte recordType,
                                               final EncodingSupport record) throws Exception
   {
      checkJournalIsLoaded();

      journalLock.readLock().lock();

      try
      {
         JournalInternalRecord updateRecordTX = new JournalAddRecordTX(false, txID, id, recordType, record);

         JournalTransaction tx = getTransactionInfo(txID);

         lockAppend.lock();
         try
         {
            JournalFile usedFile = appendRecord(updateRecordTX, false, false, tx, null);

            if (JournalImpl.TRACE_RECORDS)
            {
               JournalImpl.traceRecord("appendUpdateRecordTransactional::txID=" + txID +
                                       ",id=" +
                                       id +
                                       ", userRecordType=" +
                                       recordType +
                                       ", usedFile = " +
                                       usedFile);
            }

            tx.addPositive(usedFile, id, updateRecordTX.getEncodeSize());
         }
         finally
         {
            lockAppend.unlock();
         }
      }
      finally
      {
         journalLock.readLock().unlock();
      }
   }


   @Override
   public void appendDeleteRecordTransactional(final long txID, final long id, final EncodingSupport record) throws Exception
   {
      checkJournalIsLoaded();

      journalLock.readLock().lock();

      try
      {
         JournalInternalRecord deleteRecordTX = new JournalDeleteRecordTX(txID, id, record);

         JournalTransaction tx = getTransactionInfo(txID);

         lockAppend.lock();
         try
         {
            JournalFile usedFile = appendRecord(deleteRecordTX, false, false, tx, null);

            if (JournalImpl.TRACE_RECORDS)
            {
               JournalImpl.traceRecord("appendDeleteRecordTransactional::txID=" + txID +
                                       ", id=" +
                                       id +
                                       ", usedFile = " +
                                       usedFile);
            }

            tx.addNegative(usedFile, id);
         }
         finally
         {
            lockAppend.unlock();
         }
      }
      finally
      {
         journalLock.readLock().unlock();
      }
   }

   /**
    *
    * <p>If the system crashed after a prepare was called, it should store information that is required to bring the transaction
    *     back to a state it could be committed. </p>
    *
    * <p> transactionData allows you to store any other supporting user-data related to the transaction</p>
    *
    * <p> This method also uses the same logic applied on {@link JournalImpl#appendCommitRecord(long, boolean)}
    *
    * @param txID
    * @param transactionData extra user data for the prepare
    * @throws Exception
    */
   @Override
   public void appendPrepareRecord(final long txID,
                                   final EncodingSupport transactionData,
                                   final boolean sync,
                                   final IOCompletion callback) throws Exception
   {

      checkJournalIsLoaded();

      journalLock.readLock().lock();

     try
      {
         JournalTransaction tx = getTransactionInfo(txID);

         JournalInternalRecord prepareRecord =
                  new JournalCompleteRecordTX(TX_RECORD_TYPE.PREPARE, txID, transactionData);

         if (callback != null)
         {
            callback.storeLineUp();
         }

         lockAppend.lock();
         try
         {
            JournalFile usedFile = appendRecord(prepareRecord, true, sync, tx, callback);

            if (JournalImpl.TRACE_RECORDS)
            {
               JournalImpl.traceRecord("appendPrepareRecord::txID=" + txID + ", usedFile = " + usedFile);
            }

            tx.prepare(usedFile);
         }
         finally
         {
            lockAppend.unlock();
         }

      }
      finally
      {
         journalLock.readLock().unlock();
      }
   }

   @Override
   public void lineUpContext(IOCompletion callback)
   {
      callback.storeLineUp();
   }


   /**
    * Regarding the number of operations in a given file see {@link JournalCompleteRecordTX}.
    */
   @Override
   public void appendCommitRecord(final long txID, final boolean sync, final IOCompletion callback, boolean lineUpContext) throws Exception
   {
      checkJournalIsLoaded();

      journalLock.readLock().lock();

      try
      {
         JournalTransaction tx = transactions.remove(txID);

         if (tx == null)
         {
            throw new IllegalStateException("Cannot find tx with id " + txID);
         }

         JournalInternalRecord commitRecord = new JournalCompleteRecordTX(TX_RECORD_TYPE.COMMIT, txID, null);

         if (callback != null && lineUpContext)
         {
            callback.storeLineUp();
         }

         lockAppend.lock();
         try
         {
            JournalFile usedFile = appendRecord(commitRecord, true, sync, tx, callback);

            if (JournalImpl.TRACE_RECORDS)
            {
               JournalImpl.traceRecord("appendCommitRecord::txID=" + txID + ", usedFile = " + usedFile);
            }

            tx.commit(usedFile);
         }
         finally
         {
            lockAppend.unlock();
         }

      }
      finally
      {
         journalLock.readLock().unlock();
      }
   }

   @Override
   public void appendRollbackRecord(final long txID, final boolean sync, final IOCompletion callback) throws Exception
   {
      checkJournalIsLoaded();

      journalLock.readLock().lock();

      JournalTransaction tx = null;

      try
      {
         tx = transactions.remove(txID);

         if (tx == null)
         {
            throw new IllegalStateException("Cannot find tx with id " + txID);
         }

         JournalInternalRecord rollbackRecord = new JournalRollbackRecordTX(txID);

         if (callback != null)
         {
            callback.storeLineUp();
         }

         lockAppend.lock();
         try
         {
            JournalFile usedFile = appendRecord(rollbackRecord, false, sync, tx, callback);

            tx.rollback(usedFile);
         }
         finally
         {
            lockAppend.unlock();
         }

      }
      finally
      {
         journalLock.readLock().unlock();
      }
   }

   // XXX make it protected?
   public int getAlignment() throws Exception
   {
      return fileFactory.getAlignment();
   }

   private static final class DummyLoader implements LoaderCallback
   {
      static final LoaderCallback INSTANCE = new DummyLoader();
      public void failedTransaction(final long transactionID, final List<RecordInfo> records,
                                       final List<RecordInfo> recordsToDelete)
         {
         }

         public void updateRecord(final RecordInfo info)
         {
         }

         public void deleteRecord(final long id)
         {
         }

         public void addRecord(final RecordInfo info)
         {
         }

         public void addPreparedTransaction(final PreparedTransactionInfo preparedTransaction)
         {
         }
   }

   public synchronized JournalLoadInformation loadInternalOnly() throws Exception
   {
      return load(DummyLoader.INSTANCE, true, null);
   }

   public synchronized JournalLoadInformation loadSyncOnly(JournalState syncState) throws Exception
   {
      assert syncState == JournalState.SYNCING || syncState == JournalState.SYNCING_UP_TO_DATE;
      return load(DummyLoader.INSTANCE, true, syncState);
   }

   public JournalLoadInformation load(final List<RecordInfo> committedRecords,
                                                   final List<PreparedTransactionInfo> preparedTransactions,
                                                   final TransactionFailureCallback failureCallback) throws Exception
   {
      return load(committedRecords, preparedTransactions, failureCallback, true);
   }
   /**
    * @see JournalImpl#load(LoaderCallback)
    */
   public synchronized JournalLoadInformation load(final List<RecordInfo> committedRecords,
                                                   final List<PreparedTransactionInfo> preparedTransactions,
                                                   final TransactionFailureCallback failureCallback,
                                                   final boolean fixBadTX) throws Exception
   {
      final Set<Long> recordsToDelete = new HashSet<Long>();
      // ArrayList was taking too long to delete elements on checkDeleteSize
      final List<RecordInfo> records = new LinkedList<RecordInfo>();

      final int DELETE_FLUSH = 20000;

      JournalLoadInformation info = load(new LoaderCallback()
      {
         Runtime runtime = Runtime.getRuntime();

         private void checkDeleteSize()
         {
            // HORNETQ-482 - Flush deletes only if memory is critical
            if (recordsToDelete.size() > DELETE_FLUSH && runtime.freeMemory() < runtime.maxMemory() * 0.2)
            {
               HornetQJournalLogger.LOGGER.debug("Flushing deletes during loading, deleteCount = " + recordsToDelete.size());
               // Clean up when the list is too large, or it won't be possible to load large sets of files
               // Done as part of JBMESSAGING-1678
               Iterator<RecordInfo> iter = records.iterator();
               while (iter.hasNext())
               {
                  RecordInfo record = iter.next();

                  if (recordsToDelete.contains(record.id))
                  {
                     iter.remove();
                  }
               }

               recordsToDelete.clear();

               HornetQJournalLogger.LOGGER.debug("flush delete done");
            }
         }

         public void addPreparedTransaction(final PreparedTransactionInfo preparedTransaction)
         {
            preparedTransactions.add(preparedTransaction);
            checkDeleteSize();
         }

         public void addRecord(final RecordInfo info)
         {
            records.add(info);
            checkDeleteSize();
         }

         public void updateRecord(final RecordInfo info)
         {
            records.add(info);
            checkDeleteSize();
         }

         public void deleteRecord(final long id)
         {
            recordsToDelete.add(id);
            checkDeleteSize();
         }

         public void failedTransaction(final long transactionID,
                                       final List<RecordInfo> records,
                                       final List<RecordInfo> recordsToDelete)
         {
            if (failureCallback != null)
            {
               failureCallback.failedTransaction(transactionID, records, recordsToDelete);
            }
         }
      }, fixBadTX, null);

      for (RecordInfo record : records)
      {
         if (!recordsToDelete.contains(record.id))
         {
            committedRecords.add(record);
         }
      }

      return info;
   }


   public void scheduleCompactAndBlock(int timeout) throws Exception
   {
      final AtomicInteger errors = new AtomicInteger(0);

      final CountDownLatch latch = new CountDownLatch(1);

      compactorEnabled.set(true);

      // We can't use the executor for the compacting... or we would dead lock because of file open and creation
      // operations (that will use the executor)
      compactorExecutor.execute(new Runnable()
      {
         public void run()
         {

            try
            {
               JournalImpl.this.compact();
            }
            catch (Throwable e)
            {
               errors.incrementAndGet();
               HornetQJournalLogger.LOGGER.errorCompacting(e);
               e.printStackTrace();
            }
            finally
            {
               latch.countDown();
            }
         }
      });

      try
      {

         if (timeout <= 0)
         {
            latch.await();
         }
         else
         {
            if (!latch.await(timeout, TimeUnit.SECONDS))
            {
               throw new RuntimeException("Didn't finish compact timely");
            }
         }

         if (errors.get() > 0)
         {
            throw new RuntimeException("Error during compact, look at the logs");
         }
      }
      finally
      {
         compactorEnabled.set(false);
      }
   }

   /**
    * Note: This method can't be called from the main executor, as it will invoke other methods
    * depending on it.
    * <p>
    * Note: only synchronized methods on journal are methods responsible for the life-cycle such as
    * stop, start records will still come as this is being executed
    */
   protected synchronized void compact() throws Exception
   {
      if (compactor != null)
      {
         throw new IllegalStateException("There is pending compacting operation");
      }

      compactorLock.writeLock().lock();
      try
      {
      ArrayList<JournalFile> dataFilesToProcess = new ArrayList<JournalFile>(filesRepository.getDataFilesCount());

         boolean previousReclaimValue = isAutoReclaim();

      try
      {
         HornetQJournalLogger.LOGGER.debug("Starting compacting operation on journal");

         onCompactStart();

         // We need to guarantee that the journal is frozen for this short time
         // We don't freeze the journal as we compact, only for the short time where we replace records
         journalLock.writeLock().lock();
         try
         {
            if (state != JournalState.LOADED)
            {
               return;
            }

            onCompactLockingTheJournal();

            setAutoReclaim(false);

            // We need to move to the next file, as we need a clear start for negatives and positives counts
            moveNextFile(false);

            // Take the snapshots and replace the structures

            dataFilesToProcess.addAll(filesRepository.getDataFiles());

            filesRepository.clearDataFiles();

            if (dataFilesToProcess.size() == 0)
            {
               trace("Finishing compacting, nothing to process");
               return;
            }

            compactor = new JournalCompactor(fileFactory,
                                             this,
                                             filesRepository,
                                             records.keySet(),
                                             dataFilesToProcess.get(0).getFileID());

            for (Map.Entry<Long, JournalTransaction> entry : transactions.entrySet())
            {
               compactor.addPendingTransaction(entry.getKey(), entry.getValue().getPositiveArray());
               entry.getValue().setCompacting();
            }

            // We will calculate the new records during compacting, what will take the position the records will take
            // after compacting
            records.clear();
         }
         finally
         {
            journalLock.writeLock().unlock();
         }

         Collections.sort(dataFilesToProcess, new JournalFileComparator());

         // This is where most of the work is done, taking most of the time of the compacting routine.
         // Notice there are no locks while this is being done.

         // Read the files, and use the JournalCompactor class to create the new outputFiles, and the new collections as
         // well
         for (final JournalFile file : dataFilesToProcess)
         {
            try
            {
               JournalImpl.readJournalFile(fileFactory, file, compactor);
            }
            catch (Throwable e)
            {
               HornetQJournalLogger.LOGGER.compactReadError(file);
               throw new Exception("Error on reading compacting for " + file, e);
            }
         }

         compactor.flush();

         // pointcut for tests
         // We need to test concurrent updates on the journal, as the compacting is being performed.
         // Usually tests will use this to hold the compacting while other structures are being updated.
         onCompactDone();

         List<JournalFile> newDatafiles = null;

         JournalCompactor localCompactor = compactor;

         SequentialFile controlFile = createControlFile(dataFilesToProcess, compactor.getNewDataFiles(), null);

         journalLock.writeLock().lock();
         try
         {
            // Need to clear the compactor here, or the replay commands will send commands back (infinite loop)
            compactor = null;

            onCompactLockingTheJournal();

            newDatafiles = localCompactor.getNewDataFiles();

            // Restore newRecords created during compacting
            for (Map.Entry<Long, JournalRecord> newRecordEntry : localCompactor.getNewRecords().entrySet())
            {
               records.put(newRecordEntry.getKey(), newRecordEntry.getValue());
            }

            // Restore compacted dataFiles
            for (int i = newDatafiles.size() - 1; i >= 0; i--)
            {
               JournalFile fileToAdd = newDatafiles.get(i);
               if (JournalImpl.trace)
               {
                  JournalImpl.trace("Adding file " + fileToAdd + " back as datafile");
               }
               filesRepository.addDataFileOnTop(fileToAdd);
            }

            if (JournalImpl.trace)
            {
               JournalImpl.trace("There are " + filesRepository.getDataFilesCount() + " datafiles Now");
            }

            // Replay pending commands (including updates, deletes and commits)

            for (JournalTransaction newTransaction : localCompactor.getNewTransactions().values())
            {
               newTransaction.replaceRecordProvider(this);
            }

            localCompactor.replayPendingCommands();

            // Merge transactions back after compacting.
            // This has to be done after the replay pending commands, as we need to delete commits
            // that happened during the compacting

            for (JournalTransaction newTransaction : localCompactor.getNewTransactions().values())
            {
               if (JournalImpl.trace)
               {
                  JournalImpl.trace("Merging pending transaction " + newTransaction + " after compacting the journal");
               }
               JournalTransaction liveTransaction = transactions.get(newTransaction.getId());
               if (liveTransaction != null)
               {
                  liveTransaction.merge(newTransaction);
               }
               else
               {
                  HornetQJournalLogger.LOGGER.compactMergeError(newTransaction.getId());
               }
            }
         }
         finally
         {
            journalLock.writeLock().unlock();
         }

         // At this point the journal is unlocked. We keep renaming files while the journal is already operational
         renameFiles(dataFilesToProcess, newDatafiles);
         deleteControlFile(controlFile);

         HornetQJournalLogger.LOGGER.debug("Finished compacting on journal");

      }
      finally
      {
         // An Exception was probably thrown, and the compactor was not cleared
         if (compactor != null)
         {
            try
            {
               compactor.flush();
            }
            catch (Throwable ignored)
            {
            }

            compactor = null;
         }
         setAutoReclaim(previousReclaimValue);
         }
      }
      finally
      {
         compactorLock.writeLock().unlock();
      }

   }

   /**
    * <p>Load data accordingly to the record layouts</p>
    *
    * <p>Basic record layout:</p>
    * <table border=1>
    *   <tr><td><b>Field Name</b></td><td><b>Size</b></td></tr>
    *   <tr><td>RecordType</td><td>Byte (1)</td></tr>
    *   <tr><td>FileID</td><td>Integer (4 bytes)</td></tr>
    *   <tr><td>Compactor Counter</td><td>1 byte</td></tr>
    *   <tr><td>TransactionID <i>(if record is transactional)</i></td><td>Long (8 bytes)</td></tr>
    *   <tr><td>RecordID</td><td>Long (8 bytes)</td></tr>
    *   <tr><td>BodySize(Add, update and delete)</td><td>Integer (4 bytes)</td></tr>
    *   <tr><td>UserDefinedRecordType (If add/update only)</td><td>Byte (1)</td</tr>
    *   <tr><td>RecordBody</td><td>Byte Array (size=BodySize)</td></tr>
    *   <tr><td>Check Size</td><td>Integer (4 bytes)</td></tr>
    * </table>
    *
    * <p> The check-size is used to validate if the record is valid and complete </p>
    *
    * <p>Commit/Prepare record layout:</p>
    * <table border=1>
    *   <tr><td><b>Field Name</b></td><td><b>Size</b></td></tr>
    *   <tr><td>RecordType</td><td>Byte (1)</td></tr>
    *   <tr><td>FileID</td><td>Integer (4 bytes)</td></tr>
    *   <tr><td>Compactor Counter</td><td>1 byte</td></tr>
    *   <tr><td>TransactionID <i>(if record is transactional)</i></td><td>Long (8 bytes)</td></tr>
    *   <tr><td>ExtraDataLength (Prepares only)</td><td>Integer (4 bytes)</td></tr>
    *   <tr><td>Number Of Files (N)</td><td>Integer (4 bytes)</td></tr>
    *   <tr><td>ExtraDataBytes</td><td>Bytes (sized by ExtraDataLength)</td></tr>
    *   <tr><td>* FileID(n)</td><td>Integer (4 bytes)</td></tr>
    *   <tr><td>* NumberOfElements(n)</td><td>Integer (4 bytes)</td></tr>
    *   <tr><td>CheckSize</td><td>Integer (4 bytes)</td</tr>
    * </table>
    *
    * <p> * FileID and NumberOfElements are the transaction summary, and they will be repeated (N)umberOfFiles times </p>
    *
    * */
   public JournalLoadInformation load(final LoaderCallback loadManager) throws Exception
   {
      return load(loadManager, true, null);
   }

   /**
    * @param loadManager
    * @param changeData
    * @param replicationSync {@code true} will place
    * @return
    * @throws Exception
    */
   private synchronized JournalLoadInformation load(final LoaderCallback loadManager, final boolean changeData,
                                                    final JournalState replicationSync) throws Exception
   {
      if (state == JournalState.STOPPED || state == JournalState.LOADED)
      {
         throw new IllegalStateException("Journal " + this + " must be in " + JournalState.STARTED + " state, was " +
                  state);
      }
      if (state == replicationSync)
      {
         throw new IllegalStateException("Journal cannot be in state " + JournalState.STARTED);
      }

      checkControlFile();

      records.clear();

      filesRepository.clear();

      transactions.clear();
      currentFile = null;

      final Map<Long, TransactionHolder> loadTransactions = new LinkedHashMap<Long, TransactionHolder>();

      final List<JournalFile> orderedFiles = orderFiles();

      filesRepository.calculateNextfileID(orderedFiles);

      int lastDataPos = JournalImpl.SIZE_HEADER;

      // AtomicLong is used only as a reference, not as an Atomic value
      final AtomicLong maxID = new AtomicLong(-1);

      for (final JournalFile file : orderedFiles)
      {
         JournalImpl.trace("Loading file " + file.getFile().getFileName());

         final AtomicBoolean hasData = new AtomicBoolean(false);

         int resultLastPost = JournalImpl.readJournalFile(fileFactory, file, new JournalReaderCallback()
         {

            private void checkID(final long id)
            {
               if (id > maxID.longValue())
               {
                  maxID.set(id);
               }
            }

            public void onReadAddRecord(final RecordInfo info) throws Exception
            {
               checkID(info.id);

               hasData.set(true);

               loadManager.addRecord(info);

               records.put(info.id, new JournalRecord(file, info.data.length + JournalImpl.SIZE_ADD_RECORD + 1));
            }

            public void onReadUpdateRecord(final RecordInfo info) throws Exception
            {
               checkID(info.id);

               hasData.set(true);

               loadManager.updateRecord(info);

               JournalRecord posFiles = records.get(info.id);

               if (posFiles != null)
               {
                  // It's legal for this to be null. The file(s) with the may
                  // have been deleted
                  // just leaving some updates in this file

                  posFiles.addUpdateFile(file, info.data.length + JournalImpl.SIZE_ADD_RECORD + 1); // +1 = compact
                  // count
               }
            }

            public void onReadDeleteRecord(final long recordID) throws Exception
            {
               hasData.set(true);

               loadManager.deleteRecord(recordID);

               JournalRecord posFiles = records.remove(recordID);

               if (posFiles != null)
               {
                  posFiles.delete(file);
               }
            }

            public void onReadUpdateRecordTX(final long transactionID, final RecordInfo info) throws Exception
            {
               onReadAddRecordTX(transactionID, info);
            }

            public void onReadAddRecordTX(final long transactionID, final RecordInfo info) throws Exception
            {

               checkID(info.id);

               hasData.set(true);

               TransactionHolder tx = loadTransactions.get(transactionID);

               if (tx == null)
               {
                  tx = new TransactionHolder(transactionID);

                  loadTransactions.put(transactionID, tx);
               }

               tx.recordInfos.add(info);

               JournalTransaction tnp = transactions.get(transactionID);

               if (tnp == null)
               {
                  tnp = new JournalTransaction(transactionID, JournalImpl.this);

                  transactions.put(transactionID, tnp);
               }

               tnp.addPositive(file, info.id, info.data.length + JournalImpl.SIZE_ADD_RECORD_TX + 1); // +1 = compact
               // count
            }

            public void onReadDeleteRecordTX(final long transactionID, final RecordInfo info) throws Exception
            {
               hasData.set(true);

               TransactionHolder tx = loadTransactions.get(transactionID);

               if (tx == null)
               {
                  tx = new TransactionHolder(transactionID);

                  loadTransactions.put(transactionID, tx);
               }

               tx.recordsToDelete.add(info);

               JournalTransaction tnp = transactions.get(transactionID);

               if (tnp == null)
               {
                  tnp = new JournalTransaction(transactionID, JournalImpl.this);

                  transactions.put(transactionID, tnp);
               }

               tnp.addNegative(file, info.id);

            }

            public void onReadPrepareRecord(final long transactionID, final byte[] extraData, final int numberOfRecords) throws Exception
            {
               hasData.set(true);

               TransactionHolder tx = loadTransactions.get(transactionID);

               if (tx == null)
               {
                  // The user could choose to prepare empty transactions
                  tx = new TransactionHolder(transactionID);

                  loadTransactions.put(transactionID, tx);
               }

               tx.prepared = true;

               tx.extraData = extraData;

               JournalTransaction journalTransaction = transactions.get(transactionID);

               if (journalTransaction == null)
               {
                  journalTransaction = new JournalTransaction(transactionID, JournalImpl.this);

                  transactions.put(transactionID, journalTransaction);
               }

               boolean healthy = checkTransactionHealth(file, journalTransaction, orderedFiles, numberOfRecords);

               if (healthy)
               {
                  journalTransaction.prepare(file);
               }
               else
               {
                  HornetQJournalLogger.LOGGER.preparedTXIncomplete(transactionID);
                  tx.invalid = true;
               }
            }

            public void onReadCommitRecord(final long transactionID, final int numberOfRecords) throws Exception
            {
               TransactionHolder tx = loadTransactions.remove(transactionID);

               // The commit could be alone on its own journal-file and the
               // whole transaction body was reclaimed but not the
               // commit-record
               // So it is completely legal to not find a transaction at this
               // point
               // If we can't find it, we assume the TX was reclaimed and we
               // ignore this
               if (tx != null)
               {
                  JournalTransaction journalTransaction = transactions.remove(transactionID);

                  if (journalTransaction == null)
                  {
                     throw new IllegalStateException("Cannot find tx " + transactionID);
                  }

                  boolean healthy = checkTransactionHealth(file, journalTransaction, orderedFiles, numberOfRecords);

                  if (healthy)
                  {
                     for (RecordInfo txRecord : tx.recordInfos)
                     {
                        if (txRecord.isUpdate)
                        {
                           loadManager.updateRecord(txRecord);
                        }
                        else
                        {
                           loadManager.addRecord(txRecord);
                        }
                     }

                     for (RecordInfo deleteValue : tx.recordsToDelete)
                     {
                        loadManager.deleteRecord(deleteValue.id);
                     }

                     journalTransaction.commit(file);
                  }
                  else
                  {
                     HornetQJournalLogger.LOGGER.txMissingElements(transactionID);

                     journalTransaction.forget();
                  }

                  hasData.set(true);
               }

            }

            public void onReadRollbackRecord(final long transactionID) throws Exception
            {
               TransactionHolder tx = loadTransactions.remove(transactionID);

               // The rollback could be alone on its own journal-file and the
               // whole transaction body was reclaimed but the commit-record
               // So it is completely legal to not find a transaction at this
               // point
               if (tx != null)
               {
                  JournalTransaction tnp = transactions.remove(transactionID);

                  if (tnp == null)
                  {
                     throw new IllegalStateException("Cannot find tx " + transactionID);
                  }

                  // There is no need to validate summaries/holes on
                  // Rollbacks.. We will ignore the data anyway.
                  tnp.rollback(file);

                  hasData.set(true);
               }
            }

            public void markAsDataFile(final JournalFile file)
            {
               hasData.set(true);
            }

         });

         if (hasData.get())
         {
            lastDataPos = resultLastPost;
            filesRepository.addDataFileOnBottom(file);
         }
         else
         {
            if (changeData)
            {
               // Empty dataFiles with no data
               filesRepository.addFreeFile(file, false, false);
            }
         }
      }

      if (replicationSync == JournalState.SYNCING)
      {
         assert filesRepository.getDataFiles().isEmpty();
         setJournalState(JournalState.SYNCING);
         return new JournalLoadInformation(0, -1);
      }

      setUpCurrentFile(lastDataPos);

      setJournalState(JournalState.LOADED);

      for (TransactionHolder transaction : loadTransactions.values())
      {
         if ((!transaction.prepared || transaction.invalid) && replicationSync != JournalState.SYNCING_UP_TO_DATE)
         {
            HornetQJournalLogger.LOGGER.uncomittedTxFound(transaction.transactionID);

            if (changeData)
            {
               // I append a rollback record here, because otherwise compacting will be throwing messages because of unknown transactions
               this.appendRollbackRecord(transaction.transactionID, false);
            }

            loadManager.failedTransaction(transaction.transactionID,
                                          transaction.recordInfos,
                                          transaction.recordsToDelete);
         }
         else
         {
            for (RecordInfo info : transaction.recordInfos)
            {
               if (info.id > maxID.get())
               {
                  maxID.set(info.id);
               }
            }

            PreparedTransactionInfo info = new PreparedTransactionInfo(transaction.transactionID, transaction.extraData);

            info.records.addAll(transaction.recordInfos);

            info.recordsToDelete.addAll(transaction.recordsToDelete);

            loadManager.addPreparedTransaction(info);
         }
      }

      checkReclaimStatus();

      return new JournalLoadInformation(records.size(), maxID.longValue());
   }

   /**
    * @return true if cleanup was called
    */
   public final boolean checkReclaimStatus() throws Exception
   {

      if (compactorEnabled.get())
      {
         return false;
      }

      // We can't start reclaim while compacting is working
      while (true)
      {
         if (state != JournalImpl.JournalState.LOADED)
            return false;
         if (!isAutoReclaim())
            return false;
         if (journalLock.readLock().tryLock(250, TimeUnit.MILLISECONDS))
            break;
      }
      try
      {
         reclaimer.scan(getDataFiles());

         for (JournalFile file : filesRepository.getDataFiles())
         {
            if (file.isCanReclaim())
            {
               // File can be reclaimed or deleted
               if (JournalImpl.trace)
               {
                  JournalImpl.trace("Reclaiming file " + file);
               }

               filesRepository.removeDataFile(file);

               filesRepository.addFreeFile(file, false);
            }
         }
      }
      finally
      {
         journalLock.readLock().unlock();
      }

      return false;
   }

   private boolean needsCompact() throws Exception
   {
      JournalFile[] dataFiles = getDataFiles();

      long totalLiveSize = 0;

      for (JournalFile file : dataFiles)
      {
         totalLiveSize += file.getLiveSize();
      }

      long totalBytes = dataFiles.length * (long)fileSize;

      long compactMargin = (long)(totalBytes * compactPercentage);

      boolean needCompact = totalLiveSize < compactMargin && dataFiles.length > compactMinFiles;

      return needCompact;

   }

   private void checkCompact() throws Exception
   {
      if (compactMinFiles == 0)
      {
         // compacting is disabled
         return;
      }

      if (state != JournalState.LOADED)
      {
         return;
      }

      if (!compactorEnabled.get() && needsCompact())
      {
         scheduleCompact();
      }
   }

   private void scheduleCompact()
   {
      if (!compactorEnabled.compareAndSet(false, true))
      {
         return;
      }

      // We can't use the executor for the compacting... or we would dead lock because of file open and creation
      // operations (that will use the executor)
      compactorExecutor.execute(new Runnable()
      {
         public void run()
         {

            try
            {
               JournalImpl.this.compact();
            }
            catch (Throwable e)
            {
               HornetQJournalLogger.LOGGER.errorCompacting(e);
            }
            finally
            {
               compactorEnabled.set(false);
            }
         }
      });
   }

   // TestableJournal implementation
   // --------------------------------------------------------------

   public final void setAutoReclaim(final boolean autoReclaim)
   {
      this.autoReclaim = autoReclaim;
   }

   public final boolean isAutoReclaim()
   {
      return autoReclaim;
   }

   /* Only meant to be used in tests. */
   @Override
   public String debug() throws Exception
   {
      reclaimer.scan(getDataFiles());

      StringBuilder builder = new StringBuilder();

      for (JournalFile file : filesRepository.getDataFiles())
      {
         builder.append("DataFile:" + file +
                        " posCounter = " +
                        file.getPosCount() +
                        " reclaimStatus = " +
                        file.isCanReclaim() +
                        " live size = " +
                        file.getLiveSize() +
                        "\n");
         if (file instanceof JournalFileImpl)
         {
            builder.append(((JournalFileImpl)file).debug());

         }
      }

      for (JournalFile file : filesRepository.getFreeFiles())
      {
         builder.append("FreeFile:" + file + "\n");
      }

      if (currentFile != null)
      {
         builder.append("CurrentFile:" + currentFile + " posCounter = " + currentFile.getPosCount() + "\n");

         if (currentFile instanceof JournalFileImpl)
         {
            builder.append(((JournalFileImpl)currentFile).debug());
         }
      }
      else
      {
         builder.append("CurrentFile: No current file at this point!");
      }

      return builder.toString();
   }

   /** Method for use on testcases.
    *  It will call waitComplete on every transaction, so any assertions on the file system will be correct after this */
   public void debugWait() throws InterruptedException
   {
      fileFactory.flush();

      for (JournalTransaction tx : transactions.values())
      {
         tx.waitCallbacks();
      }

      if (filesExecutor != null && !filesExecutor.isShutdown())
      {
         // Send something to the closingExecutor, just to make sure we went
         // until its end
         final CountDownLatch latch = new CountDownLatch(1);

         filesExecutor.execute(new Runnable()
         {
            public void run()
            {
               latch.countDown();
            }
         });

         latch.await();
      }

   }

   public int getDataFilesCount()
   {
      return filesRepository.getDataFilesCount();
   }

   public JournalFile[] getDataFiles()
   {
      return filesRepository.getDataFilesArray();
   }

   public int getFreeFilesCount()
   {
      return filesRepository.getFreeFilesCount();
   }

   public int getOpenedFilesCount()
   {
      return filesRepository.getOpenedFilesCount();
   }

   public int getIDMapSize()
   {
      return records.size();
   }

   @Override
   public int getFileSize()
   {
      return fileSize;
   }

   public int getMinFiles()
   {
      return minFiles;
   }

   public String getFilePrefix()
   {
      return filesRepository.getFilePrefix();
   }

   public String getFileExtension()
   {
      return filesRepository.getFileExtension();
   }

   public int getMaxAIO()
   {
      return filesRepository.getMaxAIO();
   }

   public int getUserVersion()
   {
      return userVersion;
   }

   // In some tests we need to force the journal to move to a next file
   public void forceMoveNextFile() throws Exception
   {
      journalLock.readLock().lock();
      try
      {
         lockAppend.lock();
         try
         {
            moveNextFile(false);
            debugWait();
         }
         finally
         {
            lockAppend.unlock();
         }
      }
      finally
      {
         journalLock.readLock().unlock();
      }
   }

   public void perfBlast(final int pages)
   {
      new PerfBlast(pages).start();
   }

   // HornetQComponent implementation
   // ---------------------------------------------------

   public synchronized boolean isStarted()
   {
      return state != JournalState.STOPPED;
   }

   public synchronized void start()
   {
      if (state != JournalState.STOPPED)
      {
         throw new IllegalStateException("Journal " + this + " is not stopped, state is " + state);
      }

      filesExecutor = Executors.newSingleThreadExecutor(new ThreadFactory()
      {

         public Thread newThread(final Runnable r)
         {
            return new Thread(r, "JournalImpl::FilesExecutor");
         }
      });

      compactorExecutor = Executors.newSingleThreadExecutor(new ThreadFactory()
      {

         public Thread newThread(final Runnable r)
         {
            return new Thread(r, "JournalImpl::CompactorExecutor");
         }
      });

      filesRepository.setExecutor(filesExecutor);

      fileFactory.start();

      setJournalState(JournalState.STARTED);
   }

   public synchronized void stop() throws Exception
   {
      if (state == JournalState.STOPPED)
      {
         throw new IllegalStateException("Journal is already stopped");
      }


      journalLock.writeLock().lock();
      try
      {
      lockAppend.lock();

      try
      {

         setJournalState(JournalState.STOPPED);

         compactorExecutor.shutdown();

         if (!compactorExecutor.awaitTermination(120, TimeUnit.SECONDS))
         {
            HornetQJournalLogger.LOGGER.couldNotStopCompactor();
         }

         filesExecutor.shutdown();

         filesRepository.setExecutor(null);

         if (!filesExecutor.awaitTermination(60, TimeUnit.SECONDS))
         {
            HornetQJournalLogger.LOGGER.couldNotStopJournalExecutor();
         }

         fileFactory.deactivateBuffer();

         if (currentFile != null && currentFile.getFile().isOpen())
         {
            currentFile.getFile().close();
         }

         filesRepository.clear();

         fileFactory.stop();

         currentFile = null;
      }
      finally
      {
         lockAppend.unlock();
      }
   }
      finally
      {
         journalLock.writeLock().unlock();
      }
   }

   public int getNumberOfRecords()
   {
      return records.size();
   }


   public void disableCompact()
   {
      compactorEnabled.set(false);
   }

   public void enableCompact()
   {
      compactorEnabled.set(true);
   }


   protected SequentialFile createControlFile(final List<JournalFile> files,
                                              final List<JournalFile> newFiles,
                                              final Pair<String, String> cleanupRename) throws Exception
   {
      ArrayList<Pair<String, String>> cleanupList;
      if (cleanupRename == null)
      {
         cleanupList = null;
      }
      else
      {
         cleanupList = new ArrayList<Pair<String, String>>();
         cleanupList.add(cleanupRename);
      }
      return AbstractJournalUpdateTask.writeControlFile(fileFactory, files, newFiles, cleanupList);
   }

   protected void deleteControlFile(final SequentialFile controlFile) throws Exception
   {
      controlFile.delete();
   }

   /** being protected as testcases can override this method */
   protected void renameFiles(final List<JournalFile> oldFiles, final List<JournalFile> newFiles) throws Exception
   {

      // addFreeFiles has to be called through filesExecutor, or the fileID on the orderedFiles may end up in a wrong
      // order
      // These files are already freed, and are described on the compactor file control.
      // In case of crash they will be cleared anyways

      final CountDownLatch done = new CountDownLatch(1);

      filesExecutor.execute(new Runnable()
      {
         public void run()
         {
            for (JournalFile file : oldFiles)
            {
               try
               {
                  filesRepository.addFreeFile(file, false);
               }
               catch (Throwable e)
               {
                  HornetQJournalLogger.LOGGER.errorReinitializingFile(e, file);
               }
            }
            done.countDown();
         }
      });

      // need to wait all old files to be freed
      // to avoid a race where the CTR file is deleted before the init for these files is already done
      // what could cause a duplicate in case of a crash after the CTR is deleted and before the file is initialized
      done.await();

      for (JournalFile file : newFiles)
      {
         String newName = JournalImpl.renameExtensionFile(file.getFile().getFileName(), ".cmp");
         file.getFile().renameTo(newName);
      }

   }

   /**
    * @param name
    * @return
    */
   protected static String renameExtensionFile(String name, final String extension)
   {
      name = name.substring(0, name.lastIndexOf(extension));
      return name;
   }

   /** This is an interception point for testcases, when the compacted files are written, before replacing the data structures */
   protected void onCompactStart() throws Exception
   {
   }

   /** This is an interception point for testcases, when the compacted files are written, to be called
    *  as soon as the compactor gets a writeLock */
   protected void onCompactLockingTheJournal() throws Exception
   {
   }

   /** This is an interception point for testcases, when the compacted files are written, before replacing the data structures */
   protected void onCompactDone()
   {
   }

   // Private
   // -----------------------------------------------------------------------------

   /**
    * <p>
    * Checks for holes on the transaction (a commit written but with an incomplete transaction).
    * <p>
    * This method will validate if the transaction (PREPARE/COMMIT) is complete as stated on the
    * COMMIT-RECORD.
    * <p>
    * For details see {@link JournalCompleteRecordTX} about how the transaction-summary is recorded.
    * @param journalTransaction
    * @param orderedFiles
    * @param numberOfRecords
    * @return
    */
   private boolean checkTransactionHealth(final JournalFile currentFile,
                                          final JournalTransaction journalTransaction,
                                          final List<JournalFile> orderedFiles,
                                          final int numberOfRecords)
   {
      return journalTransaction.getCounter(currentFile) == numberOfRecords;
   }

   private static boolean isTransaction(final byte recordType)
   {
      return recordType == JournalImpl.ADD_RECORD_TX || recordType == JournalImpl.UPDATE_RECORD_TX ||
             recordType == JournalImpl.DELETE_RECORD_TX ||
             JournalImpl.isCompleteTransaction(recordType);
   }

   private static boolean isCompleteTransaction(final byte recordType)
   {
      return recordType == JournalImpl.COMMIT_RECORD || recordType == JournalImpl.PREPARE_RECORD ||
             recordType == JournalImpl.ROLLBACK_RECORD;
   }

   private static boolean isContainsBody(final byte recordType)
   {
      return recordType >= JournalImpl.ADD_RECORD && recordType <= JournalImpl.DELETE_RECORD_TX;
   }

   private static int getRecordSize(final byte recordType, final int journalVersion)
   {
      // The record size (without the variable portion)
      int recordSize = 0;
      switch (recordType)
      {
         case ADD_RECORD:
            recordSize = JournalImpl.SIZE_ADD_RECORD;
            break;
         case UPDATE_RECORD:
            recordSize = JournalImpl.SIZE_ADD_RECORD;
            break;
         case ADD_RECORD_TX:
            recordSize = JournalImpl.SIZE_ADD_RECORD_TX;
            break;
         case UPDATE_RECORD_TX:
            recordSize = JournalImpl.SIZE_ADD_RECORD_TX;
            break;
         case DELETE_RECORD:
            recordSize = JournalImpl.SIZE_DELETE_RECORD;
            break;
         case DELETE_RECORD_TX:
            recordSize = JournalImpl.SIZE_DELETE_RECORD_TX;
            break;
         case PREPARE_RECORD:
            recordSize = JournalImpl.SIZE_PREPARE_RECORD;
            break;
         case COMMIT_RECORD:
            recordSize = JournalImpl.SIZE_COMMIT_RECORD;
            break;
         case ROLLBACK_RECORD:
            recordSize = JournalImpl.SIZE_ROLLBACK_RECORD;
            break;
         default:
            // Sanity check, this was previously tested, nothing different
            // should be on this switch
            throw new IllegalStateException("Record other than expected");

      }
      if (journalVersion >= 2)
      {
         return recordSize + 1;
      }
      else
      {
         return recordSize;
      }
   }

   /**
    * @param file
    * @return
    * @throws Exception
    */
   private JournalFileImpl readFileHeader(final SequentialFile file) throws Exception
   {
      ByteBuffer bb = fileFactory.newBuffer(JournalImpl.SIZE_HEADER);

      file.read(bb);

      int journalVersion = bb.getInt();

      if (journalVersion != JournalImpl.FORMAT_VERSION)
      {
         boolean isCompatible = false;

         for (int v : JournalImpl.COMPATIBLE_VERSIONS)
         {
            if (v == journalVersion)
            {
               isCompatible = true;
            }
         }

         if (!isCompatible)
         {
            throw HornetQJournalBundle.BUNDLE.journalFileMisMatch();
         }
      }

      int readUserVersion = bb.getInt();

      if (readUserVersion != userVersion)
      {
         throw HornetQJournalBundle.BUNDLE.journalDifferentVersion();
      }

      long fileID = bb.getLong();

      fileFactory.releaseBuffer(bb);

      bb = null;

      return new JournalFileImpl(file, fileID, journalVersion);
   }

   /**
    * @param fileID
    * @param sequentialFile
    * @throws Exception
    */
   public static int initFileHeader(final SequentialFileFactory fileFactory,
                                    final SequentialFile sequentialFile,
                                    final int userVersion,
                                    final long fileID) throws Exception
   {
      // We don't need to release buffers while writing.
      ByteBuffer bb = fileFactory.newBuffer(JournalImpl.SIZE_HEADER);

      HornetQBuffer buffer = HornetQBuffers.wrappedBuffer(bb);

      JournalImpl.writeHeader(buffer, userVersion, fileID);

      bb.rewind();

      int bufferSize = bb.limit();

      sequentialFile.position(0);

      sequentialFile.writeDirect(bb, true);

      return bufferSize;
   }

   /**
    * @param buffer
    * @param userVersion
    * @param fileID
    */
   public static void writeHeader(final HornetQBuffer buffer, final int userVersion, final long fileID)
   {
      buffer.writeInt(JournalImpl.FORMAT_VERSION);

      buffer.writeInt(userVersion);

      buffer.writeLong(fileID);
   }

   /**
    * @param completeTransaction If the appendRecord is for a prepare or commit, where we should
    *           update the number of pendingTransactions on the current file
    * @throws Exception
    */
   private JournalFile appendRecord(final JournalInternalRecord encoder,
                                    final boolean completeTransaction,
                                    final boolean sync,
                                    final JournalTransaction tx,
 final IOAsyncTask parameterCallback) throws Exception
   {
      checkJournalIsLoaded();

      final IOAsyncTask callback;

      final int size = encoder.getEncodeSize();

      switchFileIfNecessary(size);

      if (tx != null)
      {
         // The callback of a transaction has to be taken inside the lock,
         // when we guarantee the currentFile will not be changed,
         // since we individualize the callback per file
         if (fileFactory.isSupportsCallbacks())
         {
            // Set the delegated callback as a parameter
            TransactionCallback txcallback = tx.getCallback(currentFile);
            if (parameterCallback != null)
            {
               txcallback.setDelegateCompletion(parameterCallback);
            }
            callback = txcallback;
         }
         else
         {
            callback = null;
         }

         // We need to add the number of records on currentFile if prepare or commit
         if (completeTransaction)
         {
            // Filling the number of pendingTransactions at the current file
            tx.fillNumberOfRecords(currentFile, encoder);
         }
      }
      else
      {
         callback = parameterCallback;
      }

      // Adding fileID
      encoder.setFileID(currentFile.getRecordID());

      if (callback != null)
      {
         currentFile.getFile().write(encoder, sync, callback);
      }
      else
      {
         currentFile.getFile().write(encoder, sync);
      }

      return currentFile;
   }

   @Override
   void scheduleReclaim()
   {
      if (state != JournalState.LOADED)
      {
         return;
      }

      if (isAutoReclaim() && !compactorEnabled.get())
      {
         compactorExecutor.execute(new Runnable()
         {
            public void run()
            {
               try
               {
                  if (!checkReclaimStatus())
                  {
                     checkCompact();
                  }
               }
               catch (Exception e)
               {
                  HornetQJournalLogger.LOGGER.errorSchedulingCompacting(e);
               }
            }
         });
      }
   }

   private JournalTransaction getTransactionInfo(final long txID)
   {
      JournalTransaction tx = transactions.get(txID);

      if (tx == null)
      {
         tx = new JournalTransaction(txID, this);

         JournalTransaction trans = transactions.putIfAbsent(txID, tx);

         if (trans != null)
         {
            tx = trans;
         }
      }

      return tx;
   }

   /**
    * @throws Exception
    */
   private void checkControlFile() throws Exception
   {
      ArrayList<String> dataFiles = new ArrayList<String>();
      ArrayList<String> newFiles = new ArrayList<String>();
      ArrayList<Pair<String, String>> renames = new ArrayList<Pair<String, String>>();

      SequentialFile controlFile = JournalCompactor.readControlFile(fileFactory, dataFiles, newFiles, renames);
      if (controlFile != null)
      {
         for (String dataFile : dataFiles)
         {
            SequentialFile file = fileFactory.createSequentialFile(dataFile, 1);
            if (file.exists())
            {
               file.delete();
            }
         }

         for (String newFile : newFiles)
         {
            SequentialFile file = fileFactory.createSequentialFile(newFile, 1);
            if (file.exists())
            {
               final String originalName = file.getFileName();
               final String newName = originalName.substring(0, originalName.lastIndexOf(".cmp"));
               file.renameTo(newName);
            }
         }

         for (Pair<String, String> rename : renames)
         {
            SequentialFile fileTmp = fileFactory.createSequentialFile(rename.getA(), 1);
            SequentialFile fileTo = fileFactory.createSequentialFile(rename.getB(), 1);
            // We should do the rename only if the tmp file still exist, or else we could
            // delete a valid file depending on where the crash occured during the control file delete
            if (fileTmp.exists())
            {
               fileTo.delete();
               fileTmp.renameTo(rename.getB());
            }
         }

         controlFile.delete();
      }

      cleanupTmpFiles(".cmp");

      cleanupTmpFiles(".tmp");

      return;
   }

   /**
    * @throws Exception
    */
   private void cleanupTmpFiles(final String extension) throws Exception
   {
      List<String> leftFiles = fileFactory.listFiles(getFileExtension() + extension);

      if (leftFiles.size() > 0)
      {
         HornetQJournalLogger.LOGGER.tempFilesLeftOpen();

         for (String fileToDelete : leftFiles)
         {
            HornetQJournalLogger.LOGGER.deletingOrphanedFile(fileToDelete);
            SequentialFile file = fileFactory.createSequentialFile(fileToDelete, 1);
            file.delete();
         }
      }
   }

   private static boolean isInvalidSize(final int fileSize, final int bufferPos, final int size)
   {
      if (size < 0)
      {
         return true;
      }
      else
      {
         final int position = bufferPos + size;

         return position > fileSize || position < 0;

      }
   }

   // Inner classes
   // ---------------------------------------------------------------------------

   // Used on Load
   private static final class TransactionHolder
   {
      public TransactionHolder(final long id)
      {
         transactionID = id;
      }

      public final long transactionID;

      public final List<RecordInfo> recordInfos = new ArrayList<RecordInfo>();

      public final List<RecordInfo> recordsToDelete = new ArrayList<RecordInfo>();

      public boolean prepared;

      public boolean invalid;

      public byte[] extraData;

   }

   private static final class JournalFileComparator implements Comparator<JournalFile>, Serializable
   {
      private static final long serialVersionUID = -6264728973604070321L;

      public int compare(final JournalFile f1, final JournalFile f2)
      {
         long id1 = f1.getFileID();
         long id2 = f2.getFileID();

         return id1 < id2 ? -1 : id1 == id2 ? 0 : 1;
      }
   }

   private final class PerfBlast extends Thread
   {
      private final int pages;

      private PerfBlast(final int pages)
      {
         super("hornetq-perfblast-thread");

         this.pages = pages;
      }

      @Override
      public void run()
      {
         lockAppend.lock();
         try
         {

            final ByteArrayEncoding byteEncoder = new ByteArrayEncoding(new byte[128 * 1024]);

            JournalInternalRecord blastRecord = new JournalInternalRecord()
            {

               @Override
               public int getEncodeSize()
               {
                  return byteEncoder.getEncodeSize();
               }

               public void encode(final HornetQBuffer buffer)
               {
                  byteEncoder.encode(buffer);
               }
            };

            for (int i = 0; i < pages; i++)
            {
               appendRecord(blastRecord, false, false, null, null);
            }
         }
         catch (Exception e)
         {
            HornetQJournalLogger.LOGGER.failedToPerfBlast(e);
         }
         finally
         {
            lockAppend.unlock();
         }
      }
   }

   public final void synchronizationLock()
   {
      compactorLock.writeLock().lock();
      journalLock.writeLock().lock();
   }

   public final void synchronizationUnlock()
   {
      try
      {
         compactorLock.writeLock().unlock();
      }
      finally
      {
         journalLock.writeLock().unlock();
      }
   }

   /**
    * Returns Map with a {@link JournalFile} for all existing files.
    * <p>
    * These are the files needed to be sent to a backup in order to synchronize it.
    * @param fileIds
    * @return map with the IDs and corresponding {@link JournalFile}s
    * @throws Exception
    */
   @Override
   public synchronized Map<Long, JournalFile> createFilesForBackupSync(long[] fileIds) throws Exception
   {
      synchronizationLock();
      try
      {
         Map<Long, JournalFile> map = new HashMap<Long, JournalFile>();
         long maxID = -1;
         for (long id : fileIds)
         {
            maxID = Math.max(maxID, id);
            map.put(Long.valueOf(id), filesRepository.createRemoteBackupSyncFile(id));
         }
         filesRepository.setNextFileID(maxID);
         return map;
      }
      finally
      {
         synchronizationUnlock();
      }
   }

   @Override
   public SequentialFileFactory getFileFactory()
   {
      return fileFactory;
   }

   /**
    * @param lastDataPos
    * @return
    * @throws Exception
    */
   protected JournalFile setUpCurrentFile(int lastDataPos) throws Exception
   {
      // Create any more files we need

      filesRepository.ensureMinFiles();

      // The current file is the last one that has data

      currentFile = filesRepository.pollLastDataFile();
      if (currentFile != null)
      {
         if (!currentFile.getFile().isOpen())
         currentFile.getFile().open();
         currentFile.getFile().position(currentFile.getFile().calculateBlockStart(lastDataPos));
      }
      else
      {
         currentFile = filesRepository.getFreeFile();
         filesRepository.openFile(currentFile, true);
      }

      fileFactory.activateBuffer(currentFile.getFile());

      filesRepository.pushOpenedFile();
      return currentFile;
   }

   /**
    * @param size
    * @return
    * @throws Exception
    */
   protected JournalFile switchFileIfNecessary(int size) throws Exception
   {
      // We take into account the fileID used on the Header
      if (size > fileSize - currentFile.getFile().calculateBlockStart(JournalImpl.SIZE_HEADER))
      {
         throw new IllegalArgumentException("Record is too large to store " + size);
      }

      if (!currentFile.getFile().fits(size))
      {
         moveNextFile(true);

         // The same check needs to be done at the new file also
         if (!currentFile.getFile().fits(size))
         {
            // Sanity check, this should never happen
            throw new IllegalStateException("Invalid logic on buffer allocation");
         }
      }
      return currentFile;
   }

   /** You need to guarantee lock.acquire() before calling this method! */
   private void moveNextFile(final boolean scheduleReclaim) throws Exception
   {
      filesRepository.closeFile(currentFile);

      currentFile = filesRepository.openFile();

      if (scheduleReclaim)
      {
         scheduleReclaim();
      }

      if (trace)
      {
         HornetQJournalLogger.LOGGER.movingNextFile(currentFile);
      }

      fileFactory.activateBuffer(currentFile.getFile());
   }

   @Override
   public void replicationSyncPreserveOldFiles()
   {
      setAutoReclaim(false);
      disableCompact();
   }

   @Override
   public void replicationSyncFinished()
   {
      enableCompact();
      setAutoReclaim(true);
   }

   @Override
   public void testCompact()
   {
      try
      {
         scheduleCompactAndBlock(60);
      }
      catch (Exception e)
      {
         throw new RuntimeException(e);
      }
   }
}
