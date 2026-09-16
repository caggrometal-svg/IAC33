import { server } from './server.js';

const port = Number(process.env.PORT || 3000);
server.listen(port, () => console.log(`IAC33 backend listening on ${port}`));
