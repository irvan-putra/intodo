import React from 'react';
import { createRoot } from 'react-dom/client';
import { FileRouter } from '@instinct/files';
import { App } from './App';
import './style.css';

createRoot(document.getElementById('root')!).render(<FileRouter><App /></FileRouter>);
