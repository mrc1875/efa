/*
 * <pre>
 * Title:        efa - elektronisches Fahrtenbuch für Ruderer
 * Copyright:    Copyright (c) 2001-2011 by Nicolas Michael
 * Website:      http://efa.nmichael.de/
 * License:      GNU General Public License v2
 *
 * @author Nicolas Michael, Martin Glade
 * @version 2</pre>
 */
package de.nmichael.efa.data.efacloud;

import de.nmichael.efa.Daten;
import de.nmichael.efa.data.BoatStatusRecord;
import de.nmichael.efa.data.LogbookRecord;
import de.nmichael.efa.data.storage.DataKey;
import de.nmichael.efa.data.storage.DataKeyIterator;
import de.nmichael.efa.data.storage.DataRecord;
import de.nmichael.efa.data.storage.EfaCloudStorage;
import de.nmichael.efa.data.types.DataTypeIntString;
import de.nmichael.efa.ex.EfaException;
import de.nmichael.efa.util.International;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;

import static de.nmichael.efa.data.LogbookRecord.*;
import static de.nmichael.efa.data.efacloud.TxRequestQueue.*;

class SynchControl {

    // The names of the tables which allow the key to be modified upon server side insert
    static final String[] tables_with_key_fixing_allowed = TableBuilder.fixid_allowed.split(" ");
    static final long clockoffsetBuffer = 600000L; // max number of millis which client clock may be offset, 10 mins
    static final long synch_upload_look_back_ms = 5 * 24 * 3600000L; // period in past to check for upload
    static final long surely_newer_after_ms = 600000L; // delta of LastModified to indicate for sure a newer record

    long timeOfLastSynch;
    long LastModifiedLimit;
    boolean synch_upload = false;
    boolean synch_upload_all = false;
    boolean synch_download_all = false;
    // efaCloudRolleBths is true if the efacloud user role is that of a boathouse. If true, this will enforce
    // pre-modification checks during download synchronization
    boolean efaCloudRolleBths = true;
    // isBoathouseApp is true, if this s run as efaBoathouse. This will enforce pre-modification checks during download
    // synchronization
    boolean isBoathouseApp = true;

    int table_fixing_index = -1;
    ArrayList<String> tables_to_synchronize = new ArrayList<String>();
    int table_synching_index = -1;
    // if a server-proposed new client key is already used, keyfixing repeats endlessly. Detect such a loop
    // This scenario is no usual case, but always a consequence of some other error. The loop will be notified.
    private int keyFixingTxCount;
    private static final int MAX_KEYFIXING_PER_SYNCH_CYCLE = 20;

    // when running an upload synchronization the server is first asked for all data keys and last modified
    // timestamps together with the last modification. They are put into records, sorted and qualified
    private final HashMap<DataKey, DataRecord> serverRecordsReturned = new HashMap<DataKey, DataRecord>();
    private final ArrayList<DataRecord> localRecordsToInsertAtServer = new ArrayList<DataRecord>();
    private final ArrayList<DataRecord> localRecordsToUpdateAtServer = new ArrayList<DataRecord>();

    private final TxRequestQueue txq;

    /**
     * Constructor. Initializes the queue reference set the time of last synch to 0L, forcing a full resynch on every
     * program restart.
     *
     * @param txq the queue reference
     */
    SynchControl(TxRequestQueue txq) {
        this.txq = txq;
        timeOfLastSynch = 0L;
    }

    /**
     * Write a log message to the synch log.
     *
     * @param logMessage     the message to be written
     * @param tablename      the name of the affected table
     * @param dataKey        the datakey of the affected record
     * @param logStateChange set true to start entry with STATECHANGE rather than SYNCH
     * @param isError        set true to log a synchronization error in the respective file.
     */
    private void logSynchMessage(String logMessage, String tablename, DataKey dataKey, boolean logStateChange, boolean isError) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String dataKeyStr = (dataKey == null) ? "" : " - " + dataKey.toString();
        String info = (logStateChange) ? "STATECHANGE " : "SYNCH ";
        String dateString = format.format(new Date()) + " INFO state, [" + tablename + dataKeyStr + "]: " + info + logMessage;
        String path = (isError) ? synchErrorFilePath : TxRequestQueue.logFilePath;
        // truncate log files,
        File f = new File(path);
        TextResource.writeContents(path, dateString, (f.length() <= 200000) || (!f.renameTo(new File(path + ".previous"))));
    }

    /**
     * Write a log message to the synch log.
     *
     * @param logMessage     the message to be written
     * @param tablename      the name of the affected table
     * @param dataKey        the datakey of the affected record
     * @param logStateChange set true to start entry with STATECHANGE rather than SYNCH
     */
    void logSynchMessage(String logMessage, String tablename, DataKey dataKey, boolean logStateChange) {
        logSynchMessage(logMessage, tablename, dataKey, logStateChange, false);
    }

    /**
     * <p>Step 1: Start the synchronization process</p><p>Start the synchronization process by appending the first
     * keyfixing request to the synching queue.</p>
     *
     * @param synch_request stet true to run an upload synchronization, false to run a download synchronization
     */
    void startSynchProcess(int synch_request) {
        table_fixing_index = 0;
        keyFixingTxCount = 0;
        synch_upload_all = synch_request == TxRequestQueue.RQ_QUEUE_START_SYNCH_UPLOAD_ALL;
        synch_upload = (synch_request == TxRequestQueue.RQ_QUEUE_START_SYNCH_UPLOAD) || synch_upload_all;
        synch_download_all = !synch_upload && (timeOfLastSynch < clockoffsetBuffer);
        String synchMessage = (synch_upload) ? International
                .getString("Synchronisation client to server (upload) starting") : International
                .getString("Synchronisation server to client (download) starting");
        logSynchMessage(synchMessage, "@all", null, true);
        // The manually triggered synchronization always takes the full set into account
        LastModifiedLimit = (synch_upload_all || synch_download_all) ? 0L : (synch_upload) ?
                System.currentTimeMillis() - synch_upload_look_back_ms : timeOfLastSynch - clockoffsetBuffer;
        timeOfLastSynch = System.currentTimeMillis();
        // request first key to be fixed. The record is empty.
        txq.appendTransaction(TX_SYNCH_QUEUE_INDEX, Transaction.TX_TYPE.KEYFIXING,
                tables_with_key_fixing_allowed[table_fixing_index], (String[]) null);
    }

    /**
     * In case an EntryId of a logbook entry is corrected, ensure that also the boat status is updated. Only by that
     * update it is ensured the trip can be closed later.
     *
     * @param currentEntryNo EntryId of the Logbook entry in the boat status
     * @param fixedEntryNo   new EntryId for this boat status
     */
    private void adjustBoatStatus(int currentEntryNo, int fixedEntryNo, long globalLock) {
        EfaCloudStorage boatstatus = Daten.tableBuilder.getPersistence("efa2boatstatus");
        DataKeyIterator it;
        try {
            it = boatstatus.getStaticIterator();
            DataKey boatstatuskey = it.getFirst();
            while (boatstatuskey != null) {
                BoatStatusRecord bsr = (BoatStatusRecord) boatstatus.get(boatstatuskey);
                if (bsr.getEntryNo() != null) {
                    int entryNo = bsr.getEntryNo().intValue();
                    if (entryNo == currentEntryNo) {
                        bsr.setEntryNo(new DataTypeIntString("" + fixedEntryNo));
                        boatstatus.update(bsr, globalLock);
                        logSynchMessage(International.getString("Korrigiere EntryNo in BoatStatus"), "efa2boatstatus",
                                boatstatuskey, false);
                    }
                }
                boatstatuskey = it.getNext();
            }
        } catch (EfaException ignored) {
            txq.logApiMessage(International.getString(
                    "Aktualisierung fehlgeschlagen beim Versuch die EntryNo zu korrigieren in BoatStatus."), 1);
        }
    }

    /**
     * <p>Step 2: Change a data key, based on the server response on a key fixing request.</p><p>This handles the
     * response on a keyfixing request, if a mismatched key was found (result code 303). Will fix the key locally and
     * create a new keyfixing request to inform the server on the execution. This function must only be called, if the
     * transaction result message contains the record too be fixed.</p><p>This function may be called by an insert
     * action, then ir does</p>
     *
     * @param tx        the transaction with the server response needed for fixing
     * @param tablename set to a table name, if just one fixing was required, e.g. after an insert. Set to "" if in
     *                  synchronizing to trigger the next table, when there is no more key to be fixed.
     */
    void fixOneKeyForTable(Transaction tx, String tablename) {
        EfaCloudStorage efaCloudStorage = Daten.tableBuilder.getPersistence(tx.tablename);
        ArrayList<DataRecord> dataRecords = efaCloudStorage.parseCsvTable(tx.getResultMessage());
        DataRecord oldDr = null;
        DataRecord newDr = null;
        String[] txRecordForFixedEntry = null;
        try {
            oldDr = efaCloudStorage.get(dataRecords.get(1).getKey());
            newDr = efaCloudStorage.get(dataRecords.get(0).getKey());
        } catch (EfaException ignored) {
        }
        if (oldDr == null)
            logSynchMessage(International.getString("Schlüsselkorrekturfehler") + ". " +
                    International.getString("Alter Schlüssel nicht vorhanden") + ": " +
                    dataRecords.get(1).getKey().toString(), tx.tablename, dataRecords.get(0).getKey(), false);
        if (newDr != null)
            logSynchMessage(International.getString("Schlüsselkorrekturfehler") + ". " +
                    International.getString("Neuer Schlüssel schon belegt") + ": " +
                    dataRecords.get(1).getKey().toString(), tx.tablename, dataRecords.get(1).getKey(), false);
        if ((oldDr != null) && (newDr == null)) {
            logSynchMessage(International.getMessage("Korrigiere Schlüssel (ursprünglich: {key})",
                    dataRecords.get(1).getKey().toString()), tx.tablename, dataRecords.get(0).getKey(), false);
            long globalLock = 0L;
            try {
                globalLock = efaCloudStorage.acquireGlobalLock();
                // the record has a link to the boat status, so it will not be allowed to be deleted. Disable
                // temporarily the check.
                efaCloudStorage.setPreModifyRecordCallbackEnabled(false);
                efaCloudStorage.modifyLocalRecord(dataRecords.get(0), globalLock, true, false, false);
                efaCloudStorage.releaseGlobalLock(globalLock);
                logSynchMessage(International.getString("Schlüsselkorektur") + ": " +
                        International.getString("Füge richtigen Datensatz hinzu") + ": " +
                        dataRecords.get(1).getKey().toString(), tx.tablename, dataRecords.get(0).getKey(), false);
                if (tx.tablename.equalsIgnoreCase("efa2logbook")) {
                    int oldEntryId = ((LogbookRecord) dataRecords.get(1)).getEntryId().intValue();
                    int newEntryId = ((LogbookRecord) dataRecords.get(0)).getEntryId().intValue();
                    EfaCloudStorage boatstatus = Daten.tableBuilder.getPersistence("efa2boatstatus");
                    globalLock = boatstatus.acquireGlobalLock();
                    adjustBoatStatus(oldEntryId, newEntryId, globalLock);
                    boatstatus.releaseGlobalLock(globalLock);
                    logSynchMessage(International.getString("Schlüsselkorektur") + ": " +
                            International.getString("Korrigiere Bootsstatus") + ": " + newEntryId,
                            "efa2boatstatus", null, false);
                }
                globalLock = efaCloudStorage.acquireGlobalLock();
                efaCloudStorage.modifyLocalRecord(dataRecords.get(1), globalLock, false, false, true);
                logSynchMessage(International.getString("Schlüsselkorektur") + ": " +
                        International.getString("Lösche falschen Datensatz") + ": " +
                        dataRecords.get(1).getKey().toString(), tx.tablename, dataRecords.get(0).getKey(), false);
                // create the record for the fixed key
                txRecordForFixedEntry = new String[dataRecords.get(0).getKeyFields().length];
                int i = 0;
                for (String keyField : dataRecords.get(0).getKeyFields()) {
                    txRecordForFixedEntry[i] = keyField + ";" +
                            CsvCodec.encodeElement(dataRecords.get(0).getAsText(keyField), CsvCodec.DEFAULT_DELIMITER,
                                    CsvCodec.DEFAULT_QUOTATION);
                    i++;
                }
            } catch (Exception e) {
                String errorMessage = International
                        .getMessage("Ausnahmefehler beim Versuch einen Schlüssel zu korrigieren in {Tabelle}: {Fehler}.",
                                tx.tablename, e.getMessage());
                txq.logApiMessage(errorMessage, 1);
                logSynchMessage(errorMessage, tx.tablename, oldDr.getKey(), false, true);
            } finally {
                efaCloudStorage.releaseGlobalLock(globalLock);
                efaCloudStorage.setPreModifyRecordCallbackEnabled(true);
            }
        } else {
            // if in synchronizing: trigger the next table, when there is no more key to be fixed.
            if (tablename.isEmpty() && (table_fixing_index < (tables_with_key_fixing_allowed.length - 1)))
                table_fixing_index++; // move to next table to avoid endless loop
        }
        // Before continuing to fix keys, check whether the limit of fixing per synch cycle is reached
        keyFixingTxCount ++;
        if (keyFixingTxCount > MAX_KEYFIXING_PER_SYNCH_CYCLE) {
            txq.logApiMessage(International.getString(
                    "Maximale Anzahl der pro Synchronisation zulässigen Korrekturen erreicht, " +
                            "vermute Endlosschleife. Abbruch der Synchronisation."), 1);
            txq.registerStateChangeRequest(RQ_QUEUE_STOP_SYNCH);
            return;
        }
        // continue anyway, if in synchronizing
        if (tablename.isEmpty())
            txq.appendTransaction(TX_SYNCH_QUEUE_INDEX, Transaction.TX_TYPE.KEYFIXING,
                    tables_with_key_fixing_allowed[table_fixing_index], txRecordForFixedEntry);
            // confirm the fixing, but only if successfully completed. If not, this will enter an endless loop between
            // client and server of retrying the keyfixing.
        else if (txRecordForFixedEntry != null)
            txq.appendTransaction(TX_PENDING_QUEUE_INDEX, Transaction.TX_TYPE.KEYFIXING, tablename, txRecordForFixedEntry);
    }

    /**
     * <p>Step 3: Continue the key fixing process with the next table, if the response to a keyfixing request is
     * 300;ok.</p><p>Increase the table_fixing_index and issue another first "keyfixing request with an empty record.
     * When all tables are done, this issues a synch @all request.</p><p>The synch @all request will filter on the
     * LastModified timestamp. For server-to-client-synchronization (downlad) it will use the last synch timestamp and
     * add a 'clockoffsetBuffer' overlapping period for the case of mismatching clocks between server and client,
     * because the LastModified value is set by the modifier, which may be the server or another client. For
     * client_to_server-Synchronisation (upload) it will use now - 30 days for CLI triggered upload, and 0L for
     * manually triggered upload, i. e. read all records.</p>
     */
    void fixKeysForNextTable() {
        table_fixing_index++;
        if (table_fixing_index < tables_with_key_fixing_allowed.length)
            txq.appendTransaction(TX_SYNCH_QUEUE_INDEX, Transaction.TX_TYPE.KEYFIXING,
                    tables_with_key_fixing_allowed[table_fixing_index], (String[]) null);
        else
            txq.appendTransaction(TX_SYNCH_QUEUE_INDEX, Transaction.TX_TYPE.SYNCH, "@all",
                    "LastModified;" + LastModifiedLimit, "?;>");
    }

    /**
     * <p>Step 4: Build the list of tables which need data synchronisation.</p><p>Based on a synch @all response this
     * functions builds the list of tables which need data synchronisation. It will start the data synchronization based
     * on the result by calling either "nextTableForDownloadSelect(null)" or nextTableForUploadSynch(null).</p>
     *
     * @param tx the transaction with the server response needed for this step
     */
    void buildSynchTableListAndStartSynch(Transaction tx) {
        String[] results = tx.getResultMessage().split(";");
        tables_to_synchronize.clear();
        StringBuilder tn = new StringBuilder();
        for (String nvp : results) {
            if (nvp.contains("=")) {
                String tablename = nvp.split("=")[0];
                String countStr = nvp.split("=")[1];
                if (TableBuilder.isServerClientCommonTable(tablename) &&  // do not synch server only tables
                        (synch_upload ||   // upload synchronization always checks all available tables.
                                !countStr.equalsIgnoreCase("0"))) {
                    tables_to_synchronize.add(tablename);
                    tn.append(tablename).append(", ");
                }
            }
        }
        // start synchronization
        table_synching_index = -1;
        if (synch_upload) {
            logSynchMessage(International.getString("Starte Upload Synchronisation für Tabellen."), tn.toString(), null,
                    false);
            nextTableForUploadSynch(null);  // Upload needs two steps: first synch, then insert & update txs
        } else {
            logSynchMessage(International.getString("Starte Download Synchronisation für Tabellen."), tn.toString(),
                    null, false);
            nextTableForDownloadSelect(null);  // Download runs in a single step, staring immediately with select
        }
    }

    /**
     * <p>Step 5, download</p><p>If a transaction is provided, read all returned records, decide whether a local record
     * shall be inserted, updated, or deleted and execute the identified modification.</p><p>Then increase the
     * table_synching_index and append a select statement for the indexed table. If the index hits the end of the set of
     * tables to be synchronized, request a state change back to normal.</p>
     *
     * @param tx the transaction with the server response needed for this step. Set null to start the cycle
     */
    void nextTableForDownloadSelect(Transaction tx) {
        if ((tx != null) && (tx.getResultMessage() != null)) {
            // a response message is received. Handle included records
            EfaCloudStorage efaCloudStorage = Daten.tableBuilder.getPersistence(tx.tablename);
            // Read all records and all last modifications.
            if (efaCloudStorage != null) {

                // if it is a full synch collect all local data Keys to find unmatched local records
                HashMap<String, DataRecord> unmatchedLocalKeys = new HashMap<String, DataRecord>();
                if (synch_download_all) {
                    try {
                        DataKeyIterator localTableIterator = efaCloudStorage.getStaticIterator();
                        DataKey dataKey = localTableIterator.getFirst();
                        DataRecord cachedRecord = efaCloudStorage.get(dataKey);
                        while (dataKey != null) {
                            unmatchedLocalKeys.put(dataKey.encodeAsString(), cachedRecord);
                            dataKey = localTableIterator.getNext();
                            cachedRecord = efaCloudStorage.get(dataKey);
                        }
                    } catch (EfaException ignored) {
                        // if combined upload fails by whatever reason, ignore it.
                    }
                }

                ArrayList<DataRecord> returnedRecords = efaCloudStorage.parseCsvTable(tx.getResultMessage());
                for (DataRecord returnedRecord : returnedRecords) {
                    // get the local record for comparison
                    DataRecord localRecord = null;
                    DataKey returnedKey = null;
                    try {
                        boolean recordHasCompleteKey = efaCloudStorage.hasCompleteKey(returnedRecord);
                        if (recordHasCompleteKey) {
                            returnedKey = efaCloudStorage.constructKey(returnedRecord);
                            if (returnedKey != null)
                                localRecord = efaCloudStorage.get(returnedKey);
                        }
                    } catch (EfaException ignored) {
                    }
                    if (returnedKey != null) {

                        // remove the reference to this record from the cached list
                        if (synch_download_all && (localRecord != null))
                            unmatchedLocalKeys.put(returnedKey.encodeAsString(), null);

                        // identify which record is to be used.
                        long serverLastModified = returnedRecord.getLastModified();
                        long localLastModified = (localRecord == null) ? 0L : localRecord.getLastModified();
                        boolean serverMoreRecent = (serverLastModified > localLastModified);

                        // Special case autoincrement counter fields: always use the larger value, even if it is older.
                        if (tx.tablename.equalsIgnoreCase("efa2autoincrement")) {
                            long lmaxReturned = 0;
                            long lmaxLocal = 0;
                            long imaxReturned = 0;
                            long imaxLocal = 0;
                            try {
                                lmaxReturned = Long.parseLong(returnedRecord.getAsString("LongValue"));
                                lmaxLocal = Long.parseLong(returnedRecord.getAsString("LongValue"));
                                imaxReturned = Long.parseLong(returnedRecord.getAsString("IntValue"));
                                imaxLocal = Long.parseLong(returnedRecord.getAsString("IntValue"));
                            } catch (Exception ignored) {
                            }
                            serverMoreRecent = (lmaxReturned > 0) ? (lmaxReturned > lmaxLocal) : (imaxReturned >
                                    imaxLocal);
                        }

                        // identify what to do, may be nothing, in particular if the record had been changed by this client
                        String lastModification = efaCloudStorage.getLastModification(returnedRecord);
                        // a legacy problem. If the database was initialized by the client, it contains copies of
                        // the local data records which have no LastModification entries.
                        // TODO: if statement can be removed once versions 2.3.0_xx become obsolete
                        if (lastModification == null)
                            lastModification = "updated";
                        boolean isDeleted = lastModification.equalsIgnoreCase("delete");
                        boolean isUpdated = lastModification.equalsIgnoreCase("update");
                        boolean insert = (localRecord == null) && !isDeleted;
                        boolean update = (localRecord != null) && serverMoreRecent && isUpdated;
                        boolean delete = (localRecord != null) && serverMoreRecent && isDeleted;
                        boolean localMoreRecent = (serverLastModified < (localLastModified - surely_newer_after_ms));
                        boolean localRecentChange = ((System.currentTimeMillis() - localLastModified) <
                                synch_upload_look_back_ms);

                        // Check whether record to update matches at least partially the new version.
                        String preUpdateRecordsCompareResult = preUpdateRecordsCompare(localRecord, returnedRecord, tx.tablename);
                        if (update && !preUpdateRecordsCompareResult.isEmpty())
                            logSynchMessage(International.getMessage(
                                            "Update-Konflikt bei Datensatz in der {type}-Synchronisation. Unterschiedlich sind: {fields}",
                                            "Download", preUpdateRecordsCompareResult) +
                                            " " + International.getString("Bitte bereinige den Datensatz manuell."), tx.tablename,
                                    localRecord.getKey(), false, true);

                        // Run update. This update will use the LastModified and ChangeCount of the record to make
                        // it a true copy of the server side record.
                        else if (insert || update || delete) {
                            long globalLock = 0;
                            try {
                                // any add modification requires a global lock.
                                globalLock = efaCloudStorage.acquireGlobalLock();
                                efaCloudStorage.modifyLocalRecord(returnedRecord, globalLock, insert, update, delete);
                                efaCloudStorage.releaseGlobalLock(globalLock);
                                logSynchMessage(International.getMessage(
                                        "Lokale Replikation des Datensatzes nach {modification} auf dem Server.",
                                        lastModification), tx.tablename, returnedRecord.getKey(), false);
                            } catch (EfaException e) {
                                String errorMessage = International.getMessage(
                                        "Ausnahmefehler bei der lokalen Modifikation eines Datensatzes in {Tabelle} ",
                                        tx.tablename) + "\n" + returnedRecord.encodeAsString() + "\n" + e.getMessage() +
                                        "\n" + e.getStackTraceAsString();
                                txq.logApiMessage(errorMessage, 1);
                                logSynchMessage(errorMessage, tx.tablename, returnedRecord.getKey(), false, true);
                            } finally {
                                efaCloudStorage.releaseGlobalLock(globalLock);
                            }
                        }
                        // local copy is more recent, upload it, if a full download was requested
                        else if (synch_download_all && localMoreRecent && localRecentChange && (localRecord != null)) {
                            efaCloudStorage.modifyServerRecord(localRecord, true, false, false, true);
                            logSynchMessage(International.getString("Aktualisiere Datensatz auf Server für Tabelle") + " ",
                                    efaCloudStorage.getStorageObjectType(), localRecord.getKey(), false);
                        }
                    }
                }
                // if a full download is executed, use this to also upload recent local changes
                if (synch_download_all) {
                    for (String unmatched : unmatchedLocalKeys.keySet()) {
                        DataRecord cachedRecord = unmatchedLocalKeys.get(unmatched);
                        if (cachedRecord != null) {
                            boolean localRecentChange = ((System.currentTimeMillis() - cachedRecord.getLastModified()) >
                                    synch_upload_look_back_ms);
                            if (localRecentChange) {
                                efaCloudStorage.modifyServerRecord(cachedRecord, true, false, false, true);
                                logSynchMessage(
                                        International.getString("Füge Datensatz auf Server ein für Tabelle") + " ", tx.tablename,
                                        cachedRecord.getKey(), false);
                            }
                        }
                    }
                }
            }
        }
        // increase table index and issue select request
        table_synching_index++;
        if (table_synching_index < tables_to_synchronize.size()) {
            txq.appendTransaction(TX_SYNCH_QUEUE_INDEX, Transaction.TX_TYPE.SELECT,
                    tables_to_synchronize.get(table_synching_index), "LastModified;" + LastModifiedLimit, "?;>");
        }
        // if there is no more Table to be synchronized, conclude
        else
            txq.registerStateChangeRequest(TxRequestQueue.RQ_QUEUE_STOP_SYNCH);
    }

    /**
     * Compares two records whether it is probable that one is the update of the other. If three or more data fields
     * differ, it is not believed that one is an update of the other and a data update conflict is created instead of
     * updating. Exception is made, if neither the role of the efaCloudUser is "bths" nor the application mode efaBths,
     * to ensure proper download synchronisation when using efa at home sporadically.
     *
     * @param dr1 DataRecord one to compare
     * @param dr2 DataRecord two to compare
     * @param tablename the table name to look up how many fields may be different.
     * @return empty String, if the records are of the same type and differ in only a tolerable amount of data fields
     */
    private String preUpdateRecordsCompare(DataRecord dr1, DataRecord dr2, String tablename) {
        if (!efaCloudRolleBths && !isBoathouseApp)
            return "";
        if ((dr1 == null) || (dr2 == null) || (dr1.getClass() != dr2.getClass()))
            return "type mismatch";
        // if archiving is executed or an archived record is restored, do not check for mismatches
        // because the archived record stub will have all fields changed.
        String archiveIDcarrier = "Name";
        if (tablename.equalsIgnoreCase("efa2persons") ||
                tablename.equalsIgnoreCase("efa2clubwork")) archiveIDcarrier = "LastName";
        if (tablename.equalsIgnoreCase("efa2messages")) archiveIDcarrier = "Subject";
        String ln1 = dr1.getAsString(archiveIDcarrier);
        String ln2 = dr2.getAsString(archiveIDcarrier);
        if (((ln1 != null) && ln1.startsWith("archiveID:")) || ((ln2 != null) && ln2.startsWith("archiveID:")))
                return "";
        int allowedMismatches = (TableBuilder.allowedMismatches.get(tablename) == null) ?
                TableBuilder.allowedMismatchesDefault : TableBuilder.allowedMismatches.get(tablename);
        if (allowedMismatches == 0) return "";    // no check required: e.g. case fahrtenhefte
        int diff = 0;
        StringBuilder fieldList = new StringBuilder();
        for (String field : dr1.getFields()) {
            if (!field.equalsIgnoreCase("ChangeCount")
                    && !field.equalsIgnoreCase("LastModified")
                    && !field.equalsIgnoreCase("LastModification")) {
                if (dr1.getAsString(field) == null) {
                    if (dr2.getAsString(field) != null) {
                        fieldList.append(field).append(", ");
                        diff ++;
                    }
                } else if (dr2.getAsString(field) == null) {
                    fieldList.append(field).append(", ");
                    diff++;
                    // Use String comparison as the compareTo() implementation throws to many different exceptions.
                } else if (!dr1.getAsString(field).equalsIgnoreCase(dr2.getAsString(field))) {
                    fieldList.append(field).append(", ");
                    diff++;
                }
            }
        }
        // special and most common case of an efa logbook record: If multiple names are replaced by UUIDs, it is still
        // the same record, although many fields did change. Identity therefore is checked on EntryId, BoatId, Date,
        // StartTime and EndTime
        if (tablename.equalsIgnoreCase("efa2logbook") && (diff >= allowedMismatches)) {
            String dr1Str = (dr1.getAsString(ENTRYID) == null) ? "null" : dr1.getAsString(ENTRYID) + ",";
            dr1Str += (dr1.getAsString(DATE) == null) ? "null" : dr1.getAsString(DATE) + ",";
            dr1Str += (dr1.getAsString(BOATID) == null) ? "null" : dr1.getAsString(BOATID) + ",";
            dr1Str += (dr1.getAsString(STARTTIME) == null) ? "null" : dr1.getAsString(STARTTIME) + ",";
            dr1Str += (dr1.getAsString(ENDTIME) == null) ? "null" : dr1.getAsString(ENDTIME);
            String dr2Str = (dr2.getAsString(ENTRYID) == null) ? "null" : dr2.getAsString(ENTRYID) + ",";
            dr2Str += (dr2.getAsString(DATE) == null) ? "null" : dr2.getAsString(DATE) + ",";
            dr2Str += (dr2.getAsString(BOATID) == null) ? "null" : dr2.getAsString(BOATID) + ",";
            dr2Str += (dr2.getAsString(STARTTIME) == null) ? "null" : dr2.getAsString(STARTTIME) + ",";
            dr2Str += (dr2.getAsString(ENDTIME) == null) ? "null" : dr2.getAsString(ENDTIME);
            if (dr1Str.equalsIgnoreCase(dr2Str))
                return "";
            else
                return fieldList.toString();
        }
        return (diff <= allowedMismatches) ? "" : fieldList.toString();
    }

    /**
     * <p>Step 5, upload</p><p>If a transaction is provided, read all returned extended keys, decide whether a server
     * record shall be inserted or updated and add the respective local record to one of two arrays of records to be
     * inserted or updated. Append a transaction to the queue per inserted (all of those first) and updated
     * records.</p><p>Then increase the * table_synching_index and append a synch statement for the indexed table. If
     * the index hits the end of the set of tables to be synchronized, request a state change back to normal.</p>
     *
     * @param tx the transaction with the server response needed for this step. Set null to start the cycle
     */
    void nextTableForUploadSynch(Transaction tx) {
        if (tx != null) {
            // a response message is received. Handle included records
            EfaCloudStorage persistence = Daten.tableBuilder.getPersistence(tx.tablename);
            if (persistence == null)
                txq.logApiMessage(International.getMessage("Konnte folgende Tabelle nicht für das Hochladen finden: {table}",
                        tx.tablename), 1);
            else {
                ArrayList<DataRecord> returnedRecords = persistence.parseCsvTable(tx.getResultMessage());
                serverRecordsReturned.clear();
                localRecordsToInsertAtServer.clear();
                localRecordsToUpdateAtServer.clear();
                for (DataRecord returnedRecord : returnedRecords)
                    serverRecordsReturned.put(returnedRecord.getKey(), returnedRecord);
                // compile the list of actionable records
                try {
                    DataKeyIterator it = persistence.getStaticIterator();
                    DataKey toCheck = it.getFirst();
                    while (toCheck != null) {
                        DataRecord localRecord = persistence.get(toCheck);
                        DataRecord serverRecord = serverRecordsReturned.get(toCheck);
                        long localLastModified = localRecord.getLastModified();
                        if (serverRecord == null) {
                            if (localLastModified > LastModifiedLimit)
                                localRecordsToInsertAtServer.add(localRecord);
                        } else if (localLastModified > serverRecord.getLastModified()) {
                            String preUpdateRecordsCompareResult = preUpdateRecordsCompare(localRecord, serverRecord, tx.tablename);
                            if (!preUpdateRecordsCompareResult.isEmpty())
                                localRecordsToUpdateAtServer.add(localRecord);
                            else {
                                logSynchMessage(International.getMessage(
                                        "Update-Konflikt bei Datensatz in der {type}-Synchronisation. Unterschiedlich sind: {fields}",
                                        "Upload", preUpdateRecordsCompareResult) +
                                        " " + International.getString("Bitte bereinige den Datensatz manuell."), tx.tablename,
                                        toCheck, false);
                            }
                        }
                        toCheck = it.getNext();
                    }
                } catch (EfaException e) {
                    txq.logApiMessage(International
                            .getMessage("Konnte nicht über die Datensätze iterieren bei Tabelle ", tx.tablename), 1);
                }
                // append all relevant transactions to the queue. This may be quite a lot and take a while to
                // be worked through.
                for (DataRecord localRecordToInsertAtServer : localRecordsToInsertAtServer) {
                    persistence.modifyServerRecord(localRecordToInsertAtServer, true, false, false, true);
                    logSynchMessage(International.getString("Füge Datensatz auf Server ein für Tabelle") + " ", tx.tablename,
                            localRecordToInsertAtServer.getKey(), false);
                }
                for (DataRecord localRecordToUpdateAtServer : localRecordsToUpdateAtServer) {
                    persistence.modifyServerRecord(localRecordToUpdateAtServer, false, true, false, true);
                    logSynchMessage(International.getString("Aktualisiere Datensatz auf Server für Tabelle") + " ",
                            tx.tablename, localRecordToUpdateAtServer.getKey(), false);
                }
            }
        }
        table_synching_index++;
        if (table_synching_index < tables_to_synchronize.size()) {
            txq.appendTransaction(TX_SYNCH_QUEUE_INDEX, Transaction.TX_TYPE.SELECT,
                    tables_to_synchronize.get(table_synching_index), "LastModified;" + LastModifiedLimit, "?;>");
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String dateString = format.format(new Date(LastModifiedLimit));
            logSynchMessage(International.getMessage("Hole Datensätze vom Server mit Modifikation nach {date}", dateString),
                    tables_to_synchronize.get(table_synching_index), null, false);
        } else {
            // This TX_QUEUE_STOP_SYNCH request will stay a while in the loop, because it will only be handled after
            // all transactions of the synch queue have been processed.
            txq.registerStateChangeRequest(TxRequestQueue.RQ_QUEUE_STOP_SYNCH);
            logSynchMessage(International.getString(
                    "Transaktionen für Synchronisation vollständig angestoßen. Warte auf Fertigstellung."), "",
                    null, false);
        }
    }

}
