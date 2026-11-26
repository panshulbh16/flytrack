// Fixture: MakeMyTrip round-trip confirmation, forwarded from Gmail (HTML part). Names, PNRs and addresses are invented.
// A .ts module so `deno test` needs no read permission.
export default `<html>
<head><meta charset="utf-8"><style>.hdr{font-weight:bold}.muted{color:#9b9b9b}</style></head>
<body>
<p>---------- Forwarded message ---------<br>
From: <b>MakeMyTrip</b> &lt;noreply@makemytrip.com&gt;<br>
Date: Tue, 24 Nov 2026 at 21:14<br>
Subject: Booking Confirmed - Bengaluru to Goa | Booking ID NF7B2C4D9E1<br>
To: &lt;rahul.v@example.com&gt;</p>
<div class="mmt">
  <table cellspacing="0" cellpadding="0">
    <tr><td class="hdr">Booking ID: NF7B2C4D9E1</td><td class="muted">Booked on 24 Nov 2026</td></tr>
  </table>
  <h2>Your flight booking is confirmed!</h2>
  <table>
    <tr><td colspan="4" class="hdr">DEPARTURE &middot; Bengaluru &rarr; Goa &middot; Sat, 12 Dec 2026</td></tr>
    <tr>
      <td><img alt="Akasa Air" src="https://img.example.test/qp.png"> Akasa Air<br><span class="muted">QP 1342</span></td>
      <td><b>06:10</b><br>BLR<br>Kempegowda International Airport, Terminal 1</td>
      <td class="muted">1h 25m<br>Non stop</td>
      <td><b>07:35</b><br>GOX<br>Manohar International Airport, Mopa</td>
    </tr>
    <tr><td>Airline PNR</td><td colspan="3"><b>K4TZ8P</b></td></tr>
  </table>
  <table>
    <tr><td colspan="4" class="hdr">RETURN &middot; Goa &rarr; Bengaluru &middot; Wed, 16 Dec 2026</td></tr>
    <tr>
      <td><img alt="IndiGo" src="https://img.example.test/6e.png"> IndiGo<br><span class="muted">6E 6814</span></td>
      <td><b>19:50</b><br>GOI<br>Dabolim Airport</td>
      <td class="muted">1h 15m<br>Non stop</td>
      <td><b>21:05</b><br>BLR<br>Kempegowda International Airport, Terminal 1</td>
    </tr>
    <tr><td>Airline PNR</td><td colspan="3"><b>H2MW9C</b></td></tr>
  </table>
  <table>
    <tr><th>Traveller</th><th>Type</th><th>Seat</th></tr>
    <tr><td>Mr Rahul Verma</td><td>Adult</td><td>&ndash;</td></tr>
  </table>
  <p class="muted">Total amount paid: &#x20B9; 9,214. Get the MakeMyTrip app for live trip updates.</p>
</div>
</body>
</html>
`;
