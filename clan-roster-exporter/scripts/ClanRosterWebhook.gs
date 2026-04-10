/**
 * Clan Roster Exporter → Google Sheets webhook
 *
 * SETUP
 * 1. Open your Google Sheet → Extensions → Apps Script.
 * 2. Paste this entire file, save (name the project anything).
 * 3. Project Settings (gear) → Script properties → Add:
 *    - ROSTER_WEBHOOK_TOKEN  = a long random secret (optional but recommended)
 *    - ROSTER_SPREADSHEET_ID = the Sheet ID from the URL (required if this project is NOT
 *      bound to your sheet). If you opened Apps Script via Sheet → Extensions, you can omit it.
 * 4. Run once: select initRosterSheet from the function dropdown → Run → authorize.
 * 5. Deploy → New deployment → Type: Web app
 *    - Execute as: Me
 *    - Who has access: Anyone (or “Anyone with Google account” if you prefer)
 * 6. Copy the Web app URL. In RuneLite plugin “Export URL”, use either:
 *      https://script.google.com/macros/s/XXXX/exec
 *    or, if you set ROSTER_WEBHOOK_TOKEN:
 *      https://script.google.com/macros/s/XXXX/exec?token=YOUR_SECRET
 *    (Bearer token in the plugin is not sent to Apps Script; use the query token instead.)
 *
 * The plugin POSTs JSON: { exportedAt, clanName, memberCount, members: [{ name, rankTitle, rank, joinDate }] }
 */

var ROSTER_SHEET_NAME = 'Clan Roster';

/** One-time: creates the tab and headers. Run from the editor. */
function initRosterSheet() {
  var ss = getTargetSpreadsheet();
  var sh = ss.getSheetByName(ROSTER_SHEET_NAME);
  if (!sh) {
    sh = ss.insertSheet(ROSTER_SHEET_NAME);
  }
  sh.clear();
  sh.getRange('A1').setValue('Last exported (UTC)');
  sh.getRange('A2').setValue('Clan name');
  sh.getRange('A3').setValue('Member count');
  sh.getRange('A5').setValue('Name');
  sh.getRange('B5').setValue('Rank title');
  sh.getRange('C5').setValue('Rank');
  sh.getRange('D5').setValue('Join date');
  sh.getRange('A1:D5').setFontWeight('bold');
  sh.setColumnWidth(1, 180);
  sh.setColumnWidth(2, 140);
  sh.setFrozenRows(5);
}

function doPost(e) {
  var out = { ok: false };

  try {
    var props = PropertiesService.getScriptProperties();
    var expected = props.getProperty('ROSTER_WEBHOOK_TOKEN');
    if (expected) {
      var token = (e.parameter && e.parameter.token) ? String(e.parameter.token) : '';
      if (token !== expected) {
        out.error = 'unauthorized';
        return jsonResponse(out, 401);
      }
    }

    if (!e.postData || !e.postData.contents) {
      out.error = 'empty body';
      return jsonResponse(out, 400);
    }

    var data = JSON.parse(e.postData.contents);
    if (!data.members || !Array.isArray(data.members)) {
      out.error = 'invalid payload: need members[]';
      return jsonResponse(out, 400);
    }

    var ss = getTargetSpreadsheet();
    var sh = ss.getSheetByName(ROSTER_SHEET_NAME);
    if (!sh) {
      initRosterSheet();
      sh = ss.getSheetByName(ROSTER_SHEET_NAME);
    }

    sh.getRange('B1').setValue(data.exportedAt || '');
    sh.getRange('B2').setValue(data.clanName || '');
    sh.getRange('B3').setValue(data.memberCount != null ? data.memberCount : data.members.length);

    var startRow = 6;
    var lastRow = sh.getLastRow();
    if (lastRow >= startRow) {
      sh.getRange(startRow, 1, lastRow, 4).clearContent();
    }

    var rows = [];
    for (var i = 0; i < data.members.length; i++) {
      var m = data.members[i];
      rows.push([
        m.name != null ? String(m.name) : '',
        m.rankTitle != null ? String(m.rankTitle) : '',
        m.rank != null ? m.rank : '',
        m.joinDate != null ? String(m.joinDate) : ''
      ]);
    }

    if (rows.length > 0) {
      sh.getRange(startRow, 1, startRow + rows.length - 1, 4).setValues(rows);
    }

    out.ok = true;
    out.rowsWritten = rows.length;
    return jsonResponse(out, 200);
  } catch (err) {
    out.error = String(err && err.message ? err.message : err);
    return jsonResponse(out, 500);
  }
}

/** Health check in a browser (GET). */
function doGet() {
  return ContentService.createTextOutput('Clan roster webhook: POST JSON here.').setMimeType(ContentService.MimeType.TEXT);
}

function getTargetSpreadsheet() {
  var props = PropertiesService.getScriptProperties();
  var id = props.getProperty('ROSTER_SPREADSHEET_ID');
  if (id) {
    return SpreadsheetApp.openById(id);
  }
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  if (!ss) {
    throw new Error('Set Script property ROSTER_SPREADSHEET_ID, or create this script from your Sheet (Extensions → Apps Script).');
  }
  return ss;
}

/**
 * Apps Script always returns HTTP 200 for web apps; status is only in JSON.
 * The RuneLite plugin treats 2xx as success — Google returns 200, so we put real status in body.
 * Optional: check response body for ok:true in your monitoring.
 */
function jsonResponse(obj, httpStatus) {
  var payload = JSON.stringify(obj);
  var output = ContentService.createTextOutput(payload).setMimeType(ContentService.MimeType.JSON);
  // Note: setResponseCode is not available on TextOutput in all runtimes; body carries ok/error.
  return output;
}
