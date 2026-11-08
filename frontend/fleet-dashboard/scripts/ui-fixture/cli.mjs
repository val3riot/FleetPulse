import { parseArgs } from 'node:util';
import { createUiFixture, SCENARIOS, TARGETS } from './server.mjs';

try {
  const { values } = parseArgs({
    options: {
      target: { type: 'string', default: 'dashboard' },
      sequence: { type: 'string', default: 'success' },
      port: { type: 'string', default: '4302' },
      'delay-ms': { type: 'string', default: '2000' },
      help: { type: 'boolean', default: false },
    },
  });
  if (values.help) {
    console.log(
      `Fixture UI locale, dati in memoria, nessuna connessione al BE.\n--target=${TARGETS.join('|')}\n--sequence=${SCENARIOS.join('|')} (sequenza separata da virgole; ultimo passo ripetuto)\n--port=4302 --delay-ms=2000\nOgni richiesta HTTP al target, inclusi i retry, consuma un passo.\nRiavviare il processo azzera dati e sequenza. Timeout simulato: 30s.\nCon committed-timeout la scrittura avviene soltanto in memoria prima dell'attesa.`,
    );
  } else {
    const port = Number(values.port);
    if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Porta non valida.');
    const fixture = createUiFixture({
      target: values.target,
      sequence: values.sequence.split(','),
      delayMs: Number(values['delay-ms']),
      onRequest: ({ method, path, attempt, scenario }) =>
        console.log(
          `${method} ${path} | ${attempt ? `tentativo ${attempt}` : 'contesto'} | ${scenario}`,
        ),
    });
    const url = await fixture.listen(port);
    console.log(
      `FIXTURE UI: ${url} | target=${values.target} | sequenza=${values.sequence}\nAvvio UI separata: npm run start:ui-check (porta 4201). Nessun dato reale.`,
    );
    let closing = false;
    const shutdown = async () => {
      if (closing) return;
      closing = true;
      await fixture.close();
    };
    process.once('SIGINT', shutdown);
    process.once('SIGTERM', shutdown);
  }
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
