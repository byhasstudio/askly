// Askly - Cloudflare Worker perantara ke Groq dan/atau Gemini.
//
// Secret (isi minimal salah satu key):
//   GROQ_API_KEY    - key dari console.groq.com (gratis)
//   GEMINI_API_KEY  - key dari aistudio.google.com/apikey
// Disarankan: APP_TOKEN (kata sandi bebas, isi sama di app)
// Opsional:
//   PROVIDER        - "groq" (default) atau "gemini": layanan yang dicoba lebih dulu
//   GROQ_MODEL      - default meta-llama/llama-4-scout-17b-16e-instruct
//   GEMINI_MODEL    - default gemini-2.5-flash
//
// Kalau layanan pertama gagal, Worker otomatis mencoba yang kedua (jika key-nya ada).

const JSON_HEADERS = { "Content-Type": "application/json; charset=utf-8" };

function reply(obj, status = 200) {
  return new Response(JSON.stringify(obj), { status, headers: JSON_HEADERS });
}

function systemPrompt(style) {
  return style === "lengkap"
    ? "Kamu asisten penjawab pertanyaan. Jawab dalam bahasa yang dipakai pengguna. Beri jawaban yang benar dan jelas, lengkap dengan penjelasan atau langkah singkat. Jangan pakai format markdown berat."
    : "Kamu asisten penjawab pertanyaan. Jawab dalam bahasa yang dipakai pengguna, sesingkat mungkin dan langsung ke inti (1-3 kalimat). Untuk soal pilihan ganda, sebutkan huruf jawaban lalu alasan dalam satu kalimat. Jangan pakai format markdown.";
}

async function askGemini(env, question, image, systemText) {
  const parts = [{ text: question || "Jawab pertanyaan atau soal yang terlihat pada gambar." }];
  if (image) parts.push({ inline_data: { mime_type: "image/jpeg", data: image } });

  const model = env.GEMINI_MODEL || "gemini-2.5-flash";
  const url =
    "https://generativelanguage.googleapis.com/v1beta/models/" +
    encodeURIComponent(model) +
    ":generateContent";

  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json", "x-goog-api-key": env.GEMINI_API_KEY },
    body: JSON.stringify({
      system_instruction: { parts: [{ text: systemText }] },
      contents: [{ role: "user", parts }],
    }),
  });
  const data = await res.json().catch(() => null);
  if (!res.ok || !data) {
    throw new Error((data && data.error && data.error.message) || "error " + res.status);
  }
  const cand = data.candidates && data.candidates[0];
  const text =
    cand && cand.content && cand.content.parts
      ? cand.content.parts.map((p) => p.text || "").join("").trim()
      : "";
  if (!text) {
    const reason =
      (data.promptFeedback && data.promptFeedback.blockReason) ||
      (cand && cand.finishReason) ||
      "tidak ada jawaban";
    throw new Error("tidak ada jawaban (" + reason + ")");
  }
  return text;
}

async function askGroq(env, question, image, systemText) {
  const content = [
    { type: "text", text: question || "Jawab pertanyaan atau soal yang terlihat pada gambar." },
  ];
  if (image) {
    content.push({ type: "image_url", image_url: { url: "data:image/jpeg;base64," + image } });
  }

  const res = await fetch("https://api.groq.com/openai/v1/chat/completions", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: "Bearer " + env.GROQ_API_KEY,
    },
    body: JSON.stringify({
      model: env.GROQ_MODEL || "meta-llama/llama-4-scout-17b-16e-instruct",
      messages: [
        { role: "system", content: systemText },
        { role: "user", content },
      ],
      max_tokens: 1024,
    }),
  });
  const data = await res.json().catch(() => null);
  if (!res.ok || !data) {
    throw new Error((data && data.error && data.error.message) || "error " + res.status);
  }
  const text =
    data.choices && data.choices[0] && data.choices[0].message
      ? String(data.choices[0].message.content || "").trim()
      : "";
  if (!text) throw new Error("tidak ada jawaban");
  return text;
}

export default {
  async fetch(request, env) {
    if (request.method !== "POST") {
      return reply({ error: "Gunakan metode POST" }, 405);
    }
    if (env.APP_TOKEN && request.headers.get("X-App-Token") !== env.APP_TOKEN) {
      return reply({ error: "Token app salah" }, 401);
    }

    const has = { groq: !!env.GROQ_API_KEY, gemini: !!env.GEMINI_API_KEY };
    const order = String(env.PROVIDER || "").toLowerCase() === "gemini"
      ? ["gemini", "groq"]
      : ["groq", "gemini"];
    const chain = order.filter((n) => has[n]);
    if (chain.length === 0) {
      return reply({ error: "Belum ada API key di Worker (GROQ_API_KEY atau GEMINI_API_KEY)" }, 500);
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

    const systemText = systemPrompt(style);
    const errors = [];
    for (const name of chain) {
      try {
        const text =
          name === "groq"
            ? await askGroq(env, question, image, systemText)
            : await askGemini(env, question, image, systemText);
        return reply({ answer: text, provider: name });
      } catch (e) {
        errors.push(name + ": " + (e && e.message ? e.message : "gagal"));
      }
    }
    return reply({ error: errors.join(" | ") }, 502);
  },
};
