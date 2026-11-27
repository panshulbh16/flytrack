import { assertEquals, assertMatch } from "@std/assert";
import {
  baseStringUri,
  hmacSha1Base64,
  normalizeParameters,
  parseFormEncoded,
  percentEncode,
  signatureBaseString,
  signRequest,
} from "../_shared/oauth1.ts";

// ---------- RFC 5849 building blocks ----------------------------------------------------------------

Deno.test("oauth1: percent encoding follows RFC 5849 3.6", () => {
  assertEquals(percentEncode("abcABC123-._~"), "abcABC123-._~");
  assertEquals(percentEncode("Ladies + Gentlemen"), "Ladies%20%2B%20Gentlemen");
  assertEquals(percentEncode("An encoded string!"), "An%20encoded%20string%21");
  assertEquals(percentEncode("Dogs, Cats & Mice"), "Dogs%2C%20Cats%20%26%20Mice");
  assertEquals(percentEncode("☃"), "%E2%98%83");
  assertEquals(percentEncode("*'()"), "%2A%27%28%29");
});

Deno.test("oauth1: base string URI (RFC 5849 3.4.1.2 examples)", () => {
  assertEquals(baseStringUri("HTTP://EXAMPLE.COM:80/r%20v/X?id=123"), "http://example.com/r%20v/X");
  assertEquals(baseStringUri("https://www.example.net:8080/?q=1"), "https://www.example.net:8080/");
  assertEquals(baseStringUri("https://api.tripit.com:443/v1/list/trip"), "https://api.tripit.com/v1/list/trip");
});

Deno.test("oauth1: RFC 5849 3.4.1.1 signature base string example", () => {
  const params: [string, string][] = [
    // query b5=%3D%253D&a3=a&c%40=&a2=r%20b, decoded
    ["b5", "=%3D"],
    ["a3", "a"],
    ["c@", ""],
    ["a2", "r b"],
    // form body c2&a3=2+q, decoded
    ["c2", ""],
    ["a3", "2 q"],
    ["oauth_consumer_key", "9djdj82h48djs9d2"],
    ["oauth_token", "kkk9d7dh3k39sjv7"],
    ["oauth_signature_method", "HMAC-SHA1"],
    ["oauth_timestamp", "137131201"],
    ["oauth_nonce", "7d8f3e4a"],
  ];
  assertEquals(
    normalizeParameters(params),
    "a2=r%20b&a3=2%20q&a3=a&b5=%3D%253D&c%40=&c2=&oauth_consumer_key=9djdj82h48djs9d2&oauth_nonce=7d8f3e4a" +
      "&oauth_signature_method=HMAC-SHA1&oauth_timestamp=137131201&oauth_token=kkk9d7dh3k39sjv7",
  );
  assertEquals(
    signatureBaseString("post", "http://example.com/request?b5=%3D%253D&a3=a&c%40=&a2=r%20b", params),
    "POST&http%3A%2F%2Fexample.com%2Frequest&a2%3Dr%2520b%26a3%3D2%2520q%26a3%3Da%26b5%3D%253D%25253D%26c%2540%3D" +
      "%26c2%3D%26oauth_consumer_key%3D9djdj82h48djs9d2%26oauth_nonce%3D7d8f3e4a%26oauth_signature_method%3DHMAC-SHA1" +
      "%26oauth_timestamp%3D137131201%26oauth_token%3Dkkk9d7dh3k39sjv7",
  );
});

Deno.test("oauth1: signRequest reads query and form parameters itself (RFC 5849 3.4.1.1)", async () => {
  const s = await signRequest(
    {
      consumerKey: "9djdj82h48djs9d2",
      consumerSecret: "j49sk3j29djd",
      token: "kkk9d7dh3k39sjv7",
      tokenSecret: "dh893hdasih9",
    },
    {
      method: "POST",
      url: "http://example.com/request?b5=%3D%253D&a3=a&c%40=&a2=r%20b",
      form: [["c2", ""], ["a3", "2 q"]],
    },
    { nonce: "7d8f3e4a", timestamp: 137131201, version: null },
  );
  assertEquals(
    s.baseString,
    "POST&http%3A%2F%2Fexample.com%2Frequest&a2%3Dr%2520b%26a3%3D2%2520q%26a3%3Da%26b5%3D%253D%25253D%26c%2540%3D" +
      "%26c2%3D%26oauth_consumer_key%3D9djdj82h48djs9d2%26oauth_nonce%3D7d8f3e4a%26oauth_signature_method%3DHMAC-SHA1" +
      "%26oauth_timestamp%3D137131201%26oauth_token%3Dkkk9d7dh3k39sjv7",
  );
});

// ---------- published signature vectors -------------------------------------------------------------

// RFC 5849 section 1.2 (and OAuth Core 1.0 appendix A) — the "photos.example.net" walkthrough.
const PHOTOS = { consumerKey: "dpf43f3p2l4k3l03", consumerSecret: "kd94hf93k423kf44" };

Deno.test("oauth1: RFC 5849 1.2 temporary credentials request", async () => {
  const s = await signRequest(PHOTOS, {
    method: "POST",
    url: "https://photos.example.net/initiate",
    extraOAuth: { oauth_callback: "http://printer.example.com/ready" },
    realm: "Photos",
  }, { nonce: "wIjqoS", timestamp: 137131200, version: null });
  assertEquals(
    s.baseString,
    "POST&https%3A%2F%2Fphotos.example.net%2Finitiate&oauth_callback%3Dhttp%253A%252F%252Fprinter.example.com" +
      "%252Fready%26oauth_consumer_key%3Ddpf43f3p2l4k3l03%26oauth_nonce%3DwIjqoS%26oauth_signature_method" +
      "%3DHMAC-SHA1%26oauth_timestamp%3D137131200",
  );
  assertEquals(s.signature, "74KNZJeDHnMBp0EMJ9ZHt/XKycU=");
  assertMatch(s.authorization, /^OAuth realm="Photos", /);
  assertMatch(s.authorization, /oauth_signature="74KNZJeDHnMBp0EMJ9ZHt%2FXKycU%3D"/);
  assertMatch(s.authorization, /oauth_callback="http%3A%2F%2Fprinter.example.com%2Fready"/);
});

Deno.test("oauth1: RFC 5849 1.2 token request (with verifier)", async () => {
  const s = await signRequest(
    { ...PHOTOS, token: "hh5s93j4hdidpola", tokenSecret: "hdhd0244k9j7ao03" },
    { method: "POST", url: "https://photos.example.net/token", extraOAuth: { oauth_verifier: "hfdp7dh39dks9884" } },
    { nonce: "walatlh", timestamp: 137131201, version: null },
  );
  assertEquals(s.signature, "gKgrFCywp7rO0OXSjdot/IHF7IU=");
});

Deno.test("oauth1: RFC 5849 1.2 protected resource request", async () => {
  const s = await signRequest(
    { ...PHOTOS, token: "nnch734d00sl2jdk", tokenSecret: "pfkkdhi9sl3r4s00" },
    { method: "GET", url: "http://photos.example.net/photos?file=vacation.jpg&size=original" },
    { nonce: "chapoH", timestamp: 137131202, version: null },
  );
  assertEquals(s.signature, "MdpQcU8iPSUjWoN/UDMsK2sui9I=");
});

Deno.test("oauth1: OAuth Core 1.0 appendix A.5 (with oauth_version)", async () => {
  const s = await signRequest(
    { ...PHOTOS, token: "nnch734d00sl2jdk", tokenSecret: "pfkkdhi9sl3r4s00" },
    { method: "GET", url: "http://photos.example.net/photos?file=vacation.jpg&size=original" },
    { nonce: "kllo9940pd9333jh", timestamp: 1191242096 },
  );
  assertEquals(
    s.baseString,
    "GET&http%3A%2F%2Fphotos.example.net%2Fphotos&file%3Dvacation.jpg%26oauth_consumer_key%3Ddpf43f3p2l4k3l03" +
      "%26oauth_nonce%3Dkllo9940pd9333jh%26oauth_signature_method%3DHMAC-SHA1%26oauth_timestamp%3D1191242096" +
      "%26oauth_token%3Dnnch734d00sl2jdk%26oauth_version%3D1.0%26size%3Doriginal",
  );
  assertEquals(s.signature, "tR3+Ty81lMeYAr/Fid0kMTYa/WM=");
});

Deno.test("oauth1: widely published form-body example (status update)", async () => {
  const s = await signRequest(
    {
      consumerKey: "xvz1evFS4wEEPTGEFPHBog",
      consumerSecret: "kAcSOqF21Fu85e7zjz7ZN2U4ZRhfV3WpwPAoE3Z7kBw",
      token: "370773112-GmHxMAgYyLbNEtIKZeRNFsMKPR9EyMZeS9weJAEb",
      tokenSecret: "LswwdoUaIvS8ltyTt5jkRh4J50vUPVVHtR2YPi5kE",
    },
    {
      method: "POST",
      url: "https://api.twitter.com/1.1/statuses/update.json?include_entities=true",
      form: { status: "Hello Ladies + Gentlemen, a signed OAuth request!" },
    },
    { nonce: "kYjzVBB8Y0ZFabxSWbWovY3uYSQ2pTgmZeNu2VS4cg", timestamp: 1318622958 },
  );
  assertEquals(s.signature, "hCtSmYh+iHYCEqBWrE7C7hYmtUk=");
});

Deno.test("oauth1: HMAC-SHA1 matches RFC 2202 test case 2", async () => {
  // key "Jefe", data "what do ya want for nothing?" → effcdf6ae5eb2fa2d27416d5f184df9c259a7c79
  assertEquals(await hmacSha1Base64("Jefe", "what do ya want for nothing?"), "7/zfauXrL6LSdBbV8YTfnCWafHk=");
});

// ---------- TripIt-shaped requests ------------------------------------------------------------------

Deno.test("oauth1: TripIt list-trip request signs the path-style URL with no query", async () => {
  const s = await signRequest(
    { consumerKey: "ck", consumerSecret: "cs", token: "at", tokenSecret: "as" },
    {
      method: "GET",
      url: "https://api.tripit.com/v1/list/trip/traveler/true/past/false/include_objects/true/format/json",
    },
    { nonce: "n0nce", timestamp: 1700000000 },
  );
  assertEquals(
    s.baseString,
    "GET&https%3A%2F%2Fapi.tripit.com%2Fv1%2Flist%2Ftrip%2Ftraveler%2Ftrue%2Fpast%2Ffalse%2Finclude_objects%2Ftrue" +
      "%2Fformat%2Fjson&oauth_consumer_key%3Dck%26oauth_nonce%3Dn0nce%26oauth_signature_method%3DHMAC-SHA1" +
      "%26oauth_timestamp%3D1700000000%26oauth_token%3Dat%26oauth_version%3D1.0",
  );
  // signing key is "cs&as"
  assertEquals(s.signature, await hmacSha1Base64("cs&as", s.baseString));
});

Deno.test("oauth1: request-token call has no oauth_token and an empty token secret", async () => {
  const s = await signRequest({ consumerKey: "ck", consumerSecret: "c&s" }, {
    method: "POST",
    url: "https://api.tripit.com/oauth/request_token",
  }, { nonce: "n", timestamp: 1 });
  assertEquals("oauth_token" in s.oauth, false);
  assertEquals(s.signature, await hmacSha1Base64("c%26s&", s.baseString));
});

Deno.test("oauth1: random nonces differ and parseFormEncoded reads token responses", async () => {
  const a = await signRequest({ consumerKey: "k", consumerSecret: "s" }, { method: "GET", url: "https://x.test/" });
  const b = await signRequest({ consumerKey: "k", consumerSecret: "s" }, { method: "GET", url: "https://x.test/" });
  assertMatch(a.oauth.oauth_nonce, /^[0-9a-f]{32}$/);
  assertEquals(a.oauth.oauth_nonce === b.oauth.oauth_nonce, false);
  assertEquals(parseFormEncoded("oauth_token=ab%2Bc&oauth_token_secret=x+y\n"), {
    oauth_token: "ab+c",
    oauth_token_secret: "x y",
  });
});
