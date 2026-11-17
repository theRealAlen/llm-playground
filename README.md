# LLM Playground: prompt consistency

A small Java 17+ experiment that sends one sentiment-classification task to a
chat-completions endpoint 20 times for each of three prompt styles. It supports
local Ollama and remote providers that use the OpenAI-compatible request and
response format:

1. Direct instructions, no examples.
2. Few-shot instructions with examples.
3. Instructions arranged in XML-like tags.

The script records the prompt, each raw response, its normalized form, and
per-prompt consistency statistics in `results.json`. It writes after every
successful request, so results from completed calls remain available if a
later request fails. It does not retry failed calls or log the API key.

## Run

Requires a JDK 17+ (the source uses Java records) and no third-party Java
libraries.

### Local Ollama

Start the Ollama app/server, then download the model:

```sh
ollama pull qwen2.5:3b
```

The local Ollama endpoint ignores API authentication, but this program requires
a non-empty `LLM_API_KEY`, so use the placeholder value `ollama`:

```sh
export LLM_API_KEY="ollama"
export LLM_API_URL="http://localhost:11434/v1/chat/completions"
export LLM_MODEL="qwen2.5:3b"
export LLM_TEMPERATURE="0.7"
```

Optionally set `LLM_TEXT` to evaluate a different input without editing or
recompiling the source:

```sh
export LLM_TEXT="The app did not disappoint; it works flawlessly."
```

### Compile and run

```sh
mkdir -p out
javac -d out src/main/java/dev/llmplayground/*.java
java -cp out dev.llmplayground.Main results.json 2
```

The example runs two calls per prompt (six calls total). To run the full
experiment with 20 calls per prompt (60 total):

```sh
java -cp out dev.llmplayground.Main results.json 20
```

The first optional argument is the output path and the second is the number of
runs per prompt. Omitting both uses `results.json` and 20 runs per prompt.

### Other OpenAI-compatible providers

Set the provider's API key in your shell; do not put credentials in this
repository. The defaults are shown below:

```sh
export LLM_API_KEY="your-api-key"
export LLM_API_URL="https://api.openai.com/v1/chat/completions"
export LLM_MODEL="gpt-4o-mini"
export LLM_TEMPERATURE="0.7"
```

`LLM_API_URL` can point to another provider implementing the OpenAI chat
completions request/response shape. Check that the selected model accepts the
configured temperature. Requests are sequential to keep the run order easy to
inspect. Cloud-provider runs can incur API costs.

## Reading the results

Each experiment includes:

- `rawOutput`: exact text returned by the model.
- `normalizedOutput`: lowercase, trimmed output with surrounding punctuation
  removed; the interior is preserved.
- `validLabel`: whether the normalized output is exactly one of the three
  allowed labels.
- `outputCounts` / `labelCounts`: observed frequencies.
- `consistencyRate`: largest exact normalized-output bucket divided by all
  completed runs. It is not a measure of correctness.
- `validLabelRate`: fraction of runs that followed the output contract.

Compare all three prompt variants using the same model, temperature, text, and
run count. A single run is not enough to conclude one prompt is more reliable.
The output JSON contains observations from the actual run; no result is
pre-filled or assumed.

## Five output checks beyond `assertEquals`

1. **Contract/schema check:** every response is one allowed label with no
   explanation or extra fields.
2. **Semantic check:** label agrees with a human-reviewed expected sentiment,
   or with a documented rubric for ambiguous cases.
3. **Property/invariant check:** outputs always belong to the allowed label
   set, independent of exact wording inside that set.
4. **Metamorphic check:** apply a meaning-preserving paraphrase to the input
   and check that the label remains stable; separately, reverse sentiment and
   check that the label changes accordingly.
5. **Statistical consistency check:** across repeated calls, track valid-label
   rate, label distribution, distinct outputs, and the most-common-output
   rate. Set an acceptable threshold from a baseline rather than expecting
   every response to match exactly.

## Observed results

In one local run with `qwen2.5:3b` through Ollama at temperature `0.7`, each
prompt style returned `positive` in all 20 runs:

| Prompt style | Valid-label rate | Consistency rate | Most common output |
| --- | ---: | ---: | --- |
| No examples | 100% (20/20) | 100% (20/20) | `positive` |
| With examples | 100% (20/20) | 100% (20/20) | `positive` |
| XML structure | 100% (20/20) | 100% (20/20) | `positive` |

For this input, all three prompt styles produced the same valid label. This
single run does not show that the model is deterministic, that it will be
correct on other inputs, or that the prompt styles are equally reliable in
general. The target text is clearly positive, so this is a basic consistency
check rather than a broad accuracy evaluation.

Results depend on the model, provider, sampling settings, and time. A follow-up
evaluation should use a human-reviewed set of positive, neutral, negative, and
ambiguous examples, and measure both semantic accuracy and output validity.
Treat the LLM as a nondeterministic external dependency; use evaluation
criteria appropriate to the task rather than relying only on exact string
equality.
