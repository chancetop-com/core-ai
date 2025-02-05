/* eslint-disable no-console */
import React, {useState, useEffect} from "react";
import {Button, Select, Input, Upload, Modal} from "antd";
import {UploadOutlined} from "@ant-design/icons";
import {RcFile, UploadChangeParam, UploadFile} from "antd/lib/upload";
import "./enduser.css";
import {ExampleAJAXWebService} from "service/ExampleAJAXWebService";
import {FileUploadResponse} from "type/api";
import TextArea from "antd/es/input/TextArea";
import Gallery from "./gallery";

const {Option} = Select;

export const EndUser = () => {
    const [suggestions, setSuggestions] = useState<string[]>([]);
    const [textContent, setTextContent] = useState("");
    const [textContents, setTextContents] = useState<string[]>(["", "", "", "", ""]);
    const [displayedTexts, setDisplayedTexts] = useState<string[]>(["", "", "", "", ""]);
    const [currentIndexes, setCurrentIndexes] = useState<number[]>([0, 0, 0, 0, 0]);
    const [imageUrl, setImageUrl] = useState("");
    const [imageUrlGenerate, setImageUrlGenerate] = useState("");
    const [imageUrlGenerates, setImageUrlGenerates] = useState<string[]>([]);
    const [idea, setIdea] = useState("");
    const [location, setLocation] = useState("UWS");
    const [language, setLanguage] = useState("en");
    const [images, setImages] = useState<string[]>([]);
    const [isLoading, setIsLoading] = useState(false);
    const [isLoading1, setIsLoading1] = useState(false);
    const [imageSizes, setImageSizes] = useState<{width: number; height: number}[]>([]);
    const [isModalVisible, setIsModalVisible] = useState(false);
    const [prompt, setprompt] = useState("Background is China Great Wall");

    const [isLoading2, setIsLoading2] = useState(false);
    const [isModalVisible2, setIsModalVisible2] = useState(false);
    const [prompt2, setprompt2] = useState("Dressed in a Santa Claus suit and hat");

    const [isLoading3, setIsLoading3] = useState(false);
    const [isModalVisible3, setIsModalVisible3] = useState(false);
    const [prompt3, setprompt3] = useState("Cartoon, Studio Ghibli style, Christmas theme");

    const [isLoading4, setIsLoading4] = useState(false);
    const [isModalVisible4, setIsModalVisible4] = useState(false);
    const [style, setStyle] = useState("https://ftidevstoragev2.blob.core.windows.net/stephen/R.jpg");
    const [prompt4, setprompt4] = useState("The Scream");

    const [isLoading5, setIsLoading5] = useState(false);
    const [prompt5, setprompt5] = useState("walking on the beach in a summer shirt");
    const [prompt6, setprompt6] = useState("wearing a spacesuit");

    const showModal = () => {
        setIsModalVisible(true);
    };

    const showModal2 = () => {
        setIsModalVisible2(true);
    };

    const showModal3 = () => {
        setIsModalVisible3(true);
    };

    const showModal4 = () => {
        setIsModalVisible4(true);
    };

    const handleCancel = () => {
        setIsModalVisible(false);
    };

    const handleCancel2 = () => {
        setIsModalVisible2(false);
    };

    const handleCancel3 = () => {
        setIsModalVisible3(false);
    };

    const handleCancel4 = () => {
        setIsModalVisible4(false);
    };

    const handleOk = async () => {
        setImageUrlGenerate("");
        setIsModalVisible(false);
        setIsLoading1(true);
        try {
            const dimenstion = getImageDimensions(imageUrl);
            const rsp = await ExampleAJAXWebService.relighting({url: imageUrl, prompt, width: (await dimenstion).width, height: (await dimenstion).height});
            setImageUrlGenerate(rsp.url);
        } finally {
            setIsLoading1(false);
        }
    };

    const handleOk2 = async () => {
        setImageUrlGenerate("");
        setIsModalVisible2(false);
        setIsLoading2(true);
        try {
            const rsp = await ExampleAJAXWebService.filling({url: imageUrl, prompt: prompt2});
            setImageUrlGenerate(rsp.url ?? "");
        } finally {
            setIsLoading2(false);
        }
    };

    const handleOk3 = async () => {
        setImageUrlGenerate("");
        setIsModalVisible3(false);
        setIsLoading3(true);
        try {
            const rsp = await ExampleAJAXWebService.ipAdapter({url: imageUrl, prompt: prompt3});
            setImageUrlGenerate(rsp.url ?? "");
        } finally {
            setIsLoading3(false);
        }
    };

    const handleOk4 = async () => {
        setImageUrlGenerate("");
        setIsModalVisible4(false);
        setIsLoading4(true);
        try {
            const rsp = await ExampleAJAXWebService.styleShape({url: imageUrl, style, prompt: prompt4});
            setImageUrlGenerate(rsp.url ?? "");
        } finally {
            setIsLoading4(false);
        }
    };

    const faceIdGenerate = async () => {
        setIsLoading5(true);
        setImageUrlGenerates([]);
        try {
            const rsp = await ExampleAJAXWebService.ipAdapterFaceId({url: imageUrl, prompt: prompt5});
            let imgs = rsp.urls ?? [];
            setImageUrlGenerates(imgs);
            const rsp2 = await ExampleAJAXWebService.ipAdapterFaceId({url: imageUrl, prompt: prompt6});
            imgs = imgs.concat(rsp2.urls ?? []);
            setImageUrlGenerates(imgs);
        } finally {
            setIsLoading5(false);
        }
    };

    const handleUpload = async (info: UploadChangeParam<UploadFile<FileUploadResponse>>) => {
        const {file} = info;
        const {response, status, error} = file as UploadFile<FileUploadResponse>;
        if (response?.url) {
            setImageUrl(response.url);
        }
    };

    const handleRemove = () => {
        setImageUrl("");
        setImageUrlGenerate("");
        setImageUrlGenerates([]);
    };

    const fetchSuggestions = async () => {
        const rsp = await ExampleAJAXWebService.endUserIdea();
        setSuggestions(rsp.ideas);
    };

    const submit = async () => {
        setTextContent("");
        setTextContents(["", "", "", "", ""]);
        setImages([]);
        setIsLoading(true);
        setDisplayedTexts(["", "", "", "", ""]);
        setCurrentIndexes([0, 0, 0, 0, 0]);
        setImageUrlGenerate("");
        setImageUrlGenerates([]);
        try {
            const rsp = await ExampleAJAXWebService.createEndUserPost({idea, location, image_url: imageUrl, language});
            setTextContent(rsp.content ?? "");
            setTextContents(language === "cn" ? rsp.contents_cn : rsp.contents);
            setImages(rsp.image_urls);
        } finally {
            setIsLoading(false);
        }
    };

    const getImageDimensions = (url: string) => {
        return new Promise<{width: number; height: number}>(resolve => {
            const img = new Image();
            img.onload = () => resolve({width: img.width, height: img.height});
            img.src = url;
        });
    };

    useEffect(() => {
        if (images.length > 0) {
            const fetchImageSizes = async () => {
                const sizes = await Promise.all(images.map(getImageDimensions));
                setImageSizes(sizes);
            };
            fetchImageSizes();
        }
    }, [images]);

    useEffect(() => {
        fetchSuggestions();
    }, []);

    useEffect(() => {
        const timers: NodeJS.Timeout[] = [];

        textContents.forEach((textContent, index) => {
            if (currentIndexes[index] < textContent.length) {
                const timer = setTimeout(() => {
                    const updatedDisplayedTexts = [...displayedTexts];
                    updatedDisplayedTexts[index] += textContent[currentIndexes[index]];
                    setDisplayedTexts(updatedDisplayedTexts);

                    const updatedCurrentIndexes = [...currentIndexes];
                    updatedCurrentIndexes[index]++;
                    setCurrentIndexes(updatedCurrentIndexes);
                }, 3); // Adjust speed here

                timers.push(timer);
            }
        });

        return () => {
            timers.forEach(clearTimeout);
        };
    }, [textContents, currentIndexes]);

    const handleSuggestionClick = (suggestion: string) => {
        setIdea(suggestion);
    };

    const handleLocationChange = (value: string) => {
        setLocation(value);
    };

    const handleLanguageChange = (value: string) => {
        setLanguage(value);
    };

    const handleUseButtonClick = (index: number) => {
        console.log(`Use button clicked for image ${index}`);
        // Add your logic here for what should happen when the "Use" button is clicked
    };

    return (
        <div className="container">
            <div className="top-section">
                <div className="idea">
                    <label>Idea:</label>
                    <Input type="text" value={idea} onChange={e => setIdea(e.target.value)} />
                </div>
                <div>
                    <label>Language:</label>
                    <Select defaultValue={language} onChange={handleLanguageChange} style={{width: 100}}>
                        <Option value="en">English</Option>
                        <Option value="cn">中文</Option>
                    </Select>
                </div>
                <div className="location">
                    <label>Location:</label>
                    <Select defaultValue={location} onChange={handleLocationChange} style={{width: 150}}>
                        <Option value="UWS">UWS</Option>
                        <Option value="Westfield">Westfield</Option>
                        <Option value="UES">UES</Option>
                        <Option value="Chelsea">Chelsea</Option>
                        <Option value="Downtown Brooklyn">Downtown Brooklyn</Option>
                        <Option value="Springfield">Springfield</Option>
                        <Option value="Quakertown">Quakertown</Option>
                    </Select>
                </div>
                <Button onClick={submit} loading={isLoading}>
                    AI Generate
                </Button>
            </div>

            <div className="suggestions">
                <label>Suggestion:</label>
                <div className="suggestion-buttons">
                    {suggestions.map((suggestion, index) => (
                        <Button key={index} className="suggestion-button" onClick={() => handleSuggestionClick(suggestion)}>
                            {suggestion}
                        </Button>
                    ))}
                </div>
            </div>

            <div className="user-content">
                <div className="user-image">
                    <label>User Photo:</label>
                    <div className="image-area-2">
                        {imageUrl ? (
                            <>
                                <img src={imageUrl} alt="Fetched from server" />
                                <div style={{paddingRight: "5px", paddingLeft: "5px"}}></div>
                                <img src={imageUrlGenerate} />
                                <Button className="delete-button" onClick={handleRemove}>
                                    Delete
                                </Button>
                                <Button className="change-button" loading={isLoading1} onClick={showModal}>
                                    Change My Backgroud
                                </Button>
                                <Button className="outfit-button" loading={isLoading2} onClick={showModal2}>
                                    Change My Outfit
                                </Button>
                                <Button className="theme-button" loading={isLoading3} onClick={showModal3}>
                                    Change Theme
                                </Button>
                                <Button className="style-button" loading={isLoading4} onClick={showModal4}>
                                    Change Style
                                </Button>
                                <Button className="random-button" loading={isLoading5} onClick={faceIdGenerate}>
                                    Generate Gallery using FaceID
                                </Button>
                            </>
                        ) : (
                            <Upload name="file" accept="image/*" action="/ajax/example/upload-file" maxCount={1} showUploadList={false} onChange={handleUpload}>
                                <Button icon={<UploadOutlined />}>Upload</Button>
                            </Upload>
                        )}
                    </div>
                    <div className="user-image">
                    <Gallery images={imageUrlGenerates} />
                    </div>
                </div>


                <Modal title="Change Background" open={isModalVisible} onOk={handleOk} onCancel={handleCancel} style={{width: "600px"}}>
                    <TextArea value={prompt} placeholder={prompt} autoSize={{minRows: 1, maxRows: 3}} />
                </Modal>

                <Modal title="Change Outfit" open={isModalVisible2} onOk={handleOk2} onCancel={handleCancel2} style={{width: "600px"}}>
                    <TextArea value={prompt2} placeholder={prompt2} autoSize={{minRows: 1, maxRows: 3}} />
                </Modal>

                <Modal title="Change Theme" open={isModalVisible3} onOk={handleOk3} onCancel={handleCancel3} style={{width: "600px"}}>
                    <TextArea value={prompt3} placeholder={prompt3} autoSize={{minRows: 1, maxRows: 3}} />
                </Modal>

                <Modal title="Change Theme" open={isModalVisible4} onOk={handleOk4} onCancel={handleCancel4} style={{width: "600px"}}>
                    <TextArea value={prompt4} placeholder={prompt4} autoSize={{minRows: 1, maxRows: 3}} />
                </Modal>

                <div className="user-main-content">
                    <label>Generated Post:</label>
                    <div>
                        {displayedTexts.map((content, index) => (
                            <div key={index} className="text-area-2">
                                <div>{content}</div>
                            </div>
                        ))}
                    </div>
                    <div className="spacer" style={{height: "5px"}} />
                    <div className="image-area-3">
                        {images.map((image, index) => (
                            <img
                                key={index}
                                src={image}
                                alt={`User content ${index}`}
                                style={{
                                    maxWidth: "100%",
                                    OObjectFit: "contain",
                                    maxHeight: "100%",
                                    width: "100%",
                                    margin: "10px",
                                    height: "600px",
                                    objectFit: "cover",
                                }}
                            />
                        ))}
                    </div>
                </div>
            </div>
        </div>
    );
};
