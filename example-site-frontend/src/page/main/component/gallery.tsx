import React from 'react';
import './Gallery.css';

const Gallery = ({ images }: {images: string[]}) => (
  <div className="gallery">
    {images.map((src, index) => (
      <div key={index} className="gallery-item">
        <img src={src} />
      </div>
    ))}
  </div>
);

export default Gallery;