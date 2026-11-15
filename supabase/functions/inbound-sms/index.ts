// Les numéros SMS ont été retirés d'AfricansBiz : cette fonction ne reçoit plus rien.
Deno.serve(() => new Response(JSON.stringify({ error: "Service retiré" }), { status: 410, headers: { "content-type": "application/json" } }));
