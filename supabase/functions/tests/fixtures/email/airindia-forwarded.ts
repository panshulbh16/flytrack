// Fixture: Air India e-ticket itinerary, forwarded as plain text with quote markers. Names, PNRs and addresses are invented.
// A .ts module so `deno test` needs no read permission.
export default `---------- Forwarded message ---------
From: Air India <eticket@airindia.com>
Date: Mon, 23 Nov 2026, 10:02
Subject: Air India e-Ticket Itinerary / Receipt - PNR 8HJQ4L
To: <sanjay.mehta@example.com>

> Dear MR SANJAY MEHTA,
>
> Thank you for choosing Air India. Please find your e-ticket itinerary below.
>
> Booking Reference (PNR): 8HJQ4L
> Ticket Number: 098-2412345678
>
> ITINERARY
> ---------------------------------------------------------------
> Flight   Date        From                    To                      Dep    Arr
> AI 2805  20DEC2026   Mumbai (BOM) T2         Delhi (DEL) T3          07:00  09:10
> AI 101   20DEC2026   Delhi (DEL) T3          New York (JFK) T4       14:15  19:25
> ---------------------------------------------------------------
> Class: Economy (T)   Baggage: 2PC
>
> Please report at the check-in counter at least 3 hours before an international departure.
> Thank you for flying Air India.
`;
