// Fixture: IndiGo booking confirmation (HTML part). Names, PNRs and addresses are invented.
// A .ts module so `deno test` needs no read permission.
export default `<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
<meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
<title>IndiGo Booking Confirmation</title>
<style type="text/css">
  body { margin:0; padding:0; font-family: Arial, Helvetica, sans-serif; }
  .pnr { font-size: 22px; color: #001b94; letter-spacing: 2px; }
  @media only screen and (max-width: 600px) { .col { width: 100% !important; } }
</style>
<script type="text/javascript">var tracking = "6E 9999 should never be read";</script>
</head>
<body style="background:#f4f4f4;">
<div style="display:none;max-height:0;overflow:hidden;">Your trip to Mumbai is confirmed&nbsp;&zwnj;&nbsp;&zwnj;&nbsp;&zwnj;&nbsp;&zwnj;</div>
<!-- header banner, campaign id 6E-2026-11 -->
<table width="600" cellpadding="0" cellspacing="0" border="0" align="center" bgcolor="#ffffff">
  <tr><td><img src="https://cdn.example.test/indigo-logo.png" alt="IndiGo" width="120" /></td></tr>
  <tr><td style="padding:20px;">Dear Ms. Ananya Rao,<br/>Thank you for choosing IndiGo. Your booking is <b>confirmed</b>.</td></tr>
  <tr>
    <td style="padding:0 20px;">
      <table width="100%">
        <tr><td>PNR / Booking Reference</td><td class="pnr"><strong>Q7XK2M</strong></td></tr>
        <tr><td>Booking Date</td><td>24 Nov 2026</td></tr>
        <tr><td>Payment Status</td><td>Completed &#8377;&nbsp;6,842</td></tr>
      </table>
    </td>
  </tr>
  <tr><td style="padding:20px;"><h3 style="margin:0;">Flight Details</h3></td></tr>
  <tr>
    <td style="padding:0 20px;">
      <table width="100%" border="0" cellpadding="6">
        <tr style="background:#001b94;color:#fff;">
          <th>Flight</th><th>Date</th><th>From</th><th>Departure</th><th>To</th><th>Arrival</th>
        </tr>
        <tr>
          <td>6E&nbsp;2341</td>
          <td>Thu, 03 Dec 2026</td>
          <td>Delhi (DEL)<br/>Terminal 1</td>
          <td>05:45</td>
          <td>Mumbai (BOM)<br/>Terminal 2</td>
          <td>08:00</td>
        </tr>
      </table>
    </td>
  </tr>
  <tr>
    <td style="padding:20px;">
      <table width="100%">
        <tr><th align="left">Passenger</th><th align="left">Seat</th><th align="left">Add-ons</th></tr>
        <tr><td>Ms. Ananya Rao</td><td>14C</td><td>6E Eats &amp; Fast Forward</td></tr>
      </table>
    </td>
  </tr>
  <tr><td style="padding:20px;font-size:12px;color:#666;">Web check-in opens 48 hours before departure &rarr; goindigo.in. Counters close 60 minutes before departure.<br/>
  This is an auto-generated email. Please do not reply.</td></tr>
</table>
</body>
</html>
`;
