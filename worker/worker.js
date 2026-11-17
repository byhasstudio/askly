// Askly - Cloudflare Worker perantara ke Gemini.
// Secret yang dibutuhkan: GEMINI_API_KEY
// Disarankan: APP_TOKEN (kata sandi bebas, isi sama di app)
// Opsional: GEMINI_MODEL (default gemini-2.5-flash)

const JSON_HEADERS = { "Content-Type": "application/json; charset=utf-8" };

function reply(obj, status = 200) {
  return new Response(JSON.stringify(obj), { status, headers: JSON_HEADERS });
}

export default {
  async fetch(request, env) {
    if (request.method !== "POST") {
      return reply({ error: "Gunakan metode POST" }, 405);
    }
    if (!env.GEMINI_API_KEY) {
      return reply({ error: "GEMINI_API_KEY belum diset di Worker" }, 500);
    }
    if (env.APP_TOKEN && request.headers.get("X-App-Token") !== env.APP_TOKEN) {
      return reply({ error: "Token app salah" }, 401);
    }

    let body;
    try {
      body = await request.json();
    } catch (e) {
      return reply({ error: "JSON tidak valid" }, 400);
    }

    const question = String(body.question || "").slice(0, 4000).trim();
    const image = typeof body.image === "string" ? body.image : null;
    const style = body.style === "lengkap" ? "lengkap" : "singkat";

    if (!question && !image) {
      return reply({ error: "Pertanyaan kosong" }, 400);
    }
    if (image && image.length > 7000000) {
      return reply({ error: "Gambar terlalu besar" }, 413);
    }

    const systemText =
      style === "lengkap"
        ? "Kamu asisten penjawab pertanyaan. Jawab dalam bahasa yang dipakai pengguna. Beri jawaban yang benar dan jelas, lengkap dengan penjelasan atau langkah singkat. Jangan pakai format markdown berat."
        : "Kamu asisten penjawab pertanyaan. Jawab dalam bahasa yang dipakai pengguna, sesingkat mungkin dan langsung ke inti (1-3 kalimat). Untuk soal pilihan ganda, sebutkan huruf jawaban lalu alasan dalam satu kalimat. Jangan pakai format markdown.";

    const parts = [];
    parts.push({
      text: question || "Jawab pertanyaan atau soal yang terlihat pada gambar.",
    });
    if (image) {
      parts.push({ inline_data: { mime_type: "image/jpeg", data: image } });
    }

    const model = env.GEMINI_MODEL || "gemini-2.5-flash";
    const url =
      "https://generativelanguage.googleapis.com/v1beta/models/" +
      encodeURIComponent(model) +
      ":generateContent";

    let res;
    try {
      res = await fetch(url, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "x-goog-api-key": env.GEMINI_API_KEY,
        },
        body: JSON.stringify({
          system_instruction: { parts: [{ text: systemText }] },
          contents: [{ role: "user", parts }],
        }),
      });
    } catch (e) {
      return reply({ error: "Gagal menghubungi Gemini" }, 502);
    }

    const data = await res.json().catch(() => null);
    if (!res.ok || !data) {
      const msg =
        (data && data.error && data.error.message) || "Gemini mengembalikan error " + res.status;
      return reply({ error: msg }, 502);
    }

    const candidate = data.candidates && data.candidates[0];
    const text =
      candidate && candidate.content && candidate.content.parts
        ? candidate.content.parts.map((p) => p.text || "").join("").trim()
        : "";

    if (!text) {
      const reason =
        (data.promptFeedback && data.promptFeedback.blockReason) ||
        (candidate && candidate.finishReason) ||
        "tidak ada jawaban";
      return reply({ error: "Gemini tidak memberi jawaban (" + reason + ")" }, 502);
    }

    return reply({ answer: text });
  },
};
