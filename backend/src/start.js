import { server } from './server.js';

const port = Number(process.env.PORT || 3000);

function shutdown(signal) {
  console.log(`IAC33 backend received ${signal}; shutting down`);
  server.close((error) => {
    if (error) {
      console.error('IAC33 backend shutdown failed', error);
      process.exitCode = 1;
    }
    process.exit();
  });
  setTimeout(() => process.exit(1), 25_000).unref();
}

server.on('error', (error) => {
  console.error('IAC33 backend server error', error);
  process.exitCode = 1;
});

process.once('SIGTERM', () => shutdown('SIGTERM'));
process.once('SIGINT', () => shutdown('SIGINT'));

server.listen(port, '0.0.0.0', () => console.log(`IAC33 backend listening on ${port}`));
